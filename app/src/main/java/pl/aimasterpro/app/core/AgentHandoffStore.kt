package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, run-scoped inbox for explicit agent-to-agent handoffs.
 *
 * CLAIMED records are intentionally not claimable again after interruption. A recipient
 * may have already performed external work, so replay could duplicate or bill an API call.
 * MASTER must explicitly resolve an uncertain claim instead of silently replaying it.
 */
object AgentHandoffStore {
    private const val PREFS = "aiforteca_agent_handoff_v1"
    private const val KEY = "messages"
    private const val MAX_MESSAGES = 400
    private const val MAX_ID = 120
    private const val MAX_PAYLOAD = 8_000
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{2,119}")

    enum class State { PENDING, CLAIMED, ACKNOWLEDGED, REJECTED }
    enum class ReadStatus { AVAILABLE, CORRUPT, UNAVAILABLE }

    data class Handoff(
        val id: String,
        val taskId: String,
        val runId: Int,
        val senderAgentId: String,
        val recipientAgentId: String,
        val payload: String,
        val state: State,
        val createdAt: Long,
        val updatedAt: Long,
        val claimedAt: Long = 0L
    )
    data class Snapshot(val status: ReadStatus, val messages: List<Handoff> = emptyList())

    /** Idempotent by messageId and serialized against cancellation of the owning run. */
    fun send(
        context: Context, taskId: String, runId: Int, messageId: String,
        senderAgentId: String, recipientAgentId: String, payload: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = MasterTaskQueueStore.withActiveRun(context, taskId, runId) {
        synchronized(this) {
            if (!valid(taskId) || !valid(messageId) || !valid(senderAgentId) ||
                !valid(recipientAgentId) || senderAgentId == recipientAgentId ||
                payload.isBlank() || payload.length > MAX_PAYLOAD || now < 0L) return@synchronized false
            if (!isAssigned(context, taskId, runId, senderAgentId) ||
                !isAssigned(context, taskId, runId, recipientAgentId)) return@synchronized false
            val safe = CommunicationLogSanitizer.sanitize(payload).take(MAX_PAYLOAD)
            if (safe.isBlank()) return@synchronized false
            val current = read(context)
            if (current.status != ReadStatus.AVAILABLE) return@synchronized false
            val existing = current.messages.firstOrNull { it.id == messageId }
            if (existing != null) return@synchronized existing.taskId == taskId && existing.runId == runId &&
                existing.senderAgentId == senderAgentId && existing.recipientAgentId == recipientAgentId &&
                existing.payload == safe
            if (current.messages.size >= MAX_MESSAGES) return@synchronized false
            write(context, current.messages + Handoff(
                messageId, taskId, runId, senderAgentId, recipientAgentId,
                safe, State.PENDING, now, now
            ))
        }
    } == true

    /** Only a PENDING item can be claimed; serialized against parent cancellation. */
    fun claim(
        context: Context, taskId: String, runId: Int, messageId: String,
        recipientAgentId: String, now: Long = System.currentTimeMillis()
    ): Handoff? = MasterTaskQueueStore.withActiveRun(context, taskId, runId) {
        synchronized(this) {
            if (!valid(taskId) || !valid(messageId) || !valid(recipientAgentId) || now < 0L) return@synchronized null
            if (!isAssigned(context, taskId, runId, recipientAgentId)) return@synchronized null
            val current = read(context)
            if (current.status != ReadStatus.AVAILABLE) return@synchronized null
            val message = current.messages.firstOrNull { it.id == messageId } ?: return@synchronized null
            if (message.taskId != taskId || message.runId != runId ||
                message.recipientAgentId != recipientAgentId || message.state != State.PENDING) return@synchronized null
            val updated = message.copy(state = State.CLAIMED, updatedAt = now, claimedAt = now)
            if (write(context, current.messages.map { if (it.id == messageId) updated else it })) updated else null
        }
    } ?: null

    /** Only the addressed recipient may acknowledge its active claim before cancellation. */
    fun acknowledge(
        context: Context, taskId: String, runId: Int, messageId: String,
        recipientAgentId: String, now: Long = System.currentTimeMillis()
    ): Boolean = MasterTaskQueueStore.withActiveRun(context, taskId, runId) {
        synchronized(this) {
            if (!valid(taskId) || !valid(messageId) || !valid(recipientAgentId) || now < 0L) return@synchronized false
            if (!isAssigned(context, taskId, runId, recipientAgentId)) return@synchronized false
            val current = read(context)
            if (current.status != ReadStatus.AVAILABLE) return@synchronized false
            val message = current.messages.firstOrNull { it.id == messageId } ?: return@synchronized false
            if (message.taskId != taskId || message.runId != runId ||
                message.recipientAgentId != recipientAgentId) return@synchronized false
            if (message.state == State.ACKNOWLEDGED) return@synchronized true
            if (message.state != State.CLAIMED || message.claimedAt <= 0L) return@synchronized false
            write(context, current.messages.map {
                if (it.id == messageId) it.copy(state = State.ACKNOWLEDGED, updatedAt = now) else it
            })
        }
    } == true

    @Synchronized
    fun snapshot(context: Context): Snapshot = read(context)

    private fun isAssigned(context: Context, taskId: String, runId: Int, agentId: String): Boolean {
        val queue = MasterTaskQueueStore.snapshot(context)
        if (queue.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) return false
        val task = queue.tasks.firstOrNull { it.id == taskId } ?: return false
        return task.status == MasterTaskQueueStore.Status.RUNNING &&
            task.runId == runId && agentId in task.assignedAgentIds
    }


    private fun valid(value: String): Boolean = value.length <= MAX_ID && idPattern.matches(value)

    private fun read(context: Context): Snapshot {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return Snapshot(ReadStatus.AVAILABLE)
        return try {
            val array = JSONArray(raw)
            if (array.length() > MAX_MESSAGES) return Snapshot(ReadStatus.CORRUPT)
            val messages = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Handoff(
                    item.getString("id"), item.getString("taskId"), item.getInt("runId"),
                    item.getString("senderAgentId"), item.getString("recipientAgentId"),
                    item.getString("payload"), State.valueOf(item.getString("state")),
                    item.getLong("createdAt"), item.getLong("updatedAt"),
                    item.optLong("claimedAt", 0L)
                )
            }
            if (messages.any {
                !valid(it.id) || !valid(it.taskId) || !valid(it.senderAgentId) ||
                    !valid(it.recipientAgentId) || it.runId <= 0 || it.payload.isBlank() ||
                    it.payload.length > MAX_PAYLOAD || it.createdAt < 0L ||
                    it.updatedAt < 0L || it.claimedAt < 0L
            } || messages.map { it.id }.distinct().size != messages.size) {
                Snapshot(ReadStatus.CORRUPT)
            } else Snapshot(ReadStatus.AVAILABLE, messages)
        } catch (_: Exception) {
            Snapshot(ReadStatus.CORRUPT)
        }
    }

    private fun write(context: Context, messages: List<Handoff>): Boolean {
        if (messages.size > MAX_MESSAGES) return false
        val array = JSONArray()
        messages.forEach { m ->
            array.put(JSONObject()
                .put("id", m.id).put("taskId", m.taskId).put("runId", m.runId)
                .put("senderAgentId", m.senderAgentId).put("recipientAgentId", m.recipientAgentId)
                .put("payload", m.payload).put("state", m.state.name)
                .put("createdAt", m.createdAt).put("updatedAt", m.updatedAt)
                .put("claimedAt", m.claimedAt))
        }
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).commit()
    }
}
