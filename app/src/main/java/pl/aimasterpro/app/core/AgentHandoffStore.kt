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
    private const val MAX_RAW_CHARS = 4_000_000
    private const val MAX_ID = 120
    private const val MAX_PAYLOAD = 8_000
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{2,119}")

    enum class State { PENDING, CLAIMED, ACKNOWLEDGED, REJECTED, MANUALLY_RESOLVED }
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
        val claimedAt: Long = 0L,
        val resolvedAt: Long = 0L,
        val resolutionActor: String = "",
        val resolutionReason: String = ""
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
                payload.isBlank() || payload.length > MAX_PAYLOAD || now <= 0L) return@synchronized false
            if (!isAssigned(context, taskId, runId, senderAgentId) ||
                !isAssigned(context, taskId, runId, recipientAgentId)) return@synchronized false
            val safe = CommunicationLogSanitizer.sanitize(payload)
            // Sanitization may expand short credential values; never persist a silently cut message.
            if (safe.isBlank() || safe.length > MAX_PAYLOAD) return@synchronized false
            val current = read(context)
            if (current.status != ReadStatus.AVAILABLE) return@synchronized false
            val existing = current.messages.firstOrNull { it.id == messageId }
            if (existing != null) return@synchronized existing.taskId == taskId && existing.runId == runId &&
                existing.senderAgentId == senderAgentId && existing.recipientAgentId == recipientAgentId &&
                existing.payload == safe
            // Keep the inbox bounded without letting old terminal records permanently
            // exhaust capacity. Never evict PENDING or CLAIMED work: CLAIMED may represent
            // an external provider request whose outcome is uncertain.
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
            if (!valid(taskId) || !valid(messageId) || !valid(recipientAgentId) || now <= 0L) return@synchronized null
            if (!isAssigned(context, taskId, runId, recipientAgentId)) return@synchronized null
            val current = read(context)
            if (current.status != ReadStatus.AVAILABLE) return@synchronized null
            val message = current.messages.firstOrNull { it.id == messageId } ?: return@synchronized null
            if (message.taskId != taskId || message.runId != runId ||
                message.recipientAgentId != recipientAgentId || message.state != State.PENDING ||
                now < message.createdAt) return@synchronized null
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
            if (message.state != State.CLAIMED || message.claimedAt <= 0L ||
                now < message.claimedAt || now < message.createdAt) return@synchronized false
            write(context, current.messages.map {
                if (it.id == messageId) it.copy(state = State.ACKNOWLEDGED, updatedAt = now) else it
            })
        }
    } == true

    /** Only the addressed recipient may reject its own active claim with a durable reason. */
    fun reject(
        context: Context, taskId: String, runId: Int, messageId: String,
        recipientAgentId: String, reason: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = MasterTaskQueueStore.withActiveRun(context, taskId, runId) {
        synchronized(this) {
            if (!valid(taskId) || !valid(messageId) || !valid(recipientAgentId) ||
                reason.isBlank() || reason.length > 300 || now <= 0L) return@synchronized false
            if (!isAssigned(context, taskId, runId, recipientAgentId)) return@synchronized false
            val safeReason = CommunicationLogSanitizer.sanitize(reason.trim())
            if (safeReason.isBlank() || safeReason.length > 300) return@synchronized false
            val current = read(context)
            if (current.status != ReadStatus.AVAILABLE) return@synchronized false
            val message = current.messages.firstOrNull { it.id == messageId } ?: return@synchronized false
            if (message.taskId != taskId || message.runId != runId ||
                message.recipientAgentId != recipientAgentId) return@synchronized false
            if (message.state == State.REJECTED) return@synchronized message.resolutionReason == safeReason
            if (message.state != State.CLAIMED || message.claimedAt <= 0L ||
                now < message.claimedAt || now < message.createdAt) return@synchronized false
            write(context, current.messages.map {
                if (it.id == messageId) it.copy(
                    state = State.REJECTED,
                    updatedAt = now,
                    resolutionReason = safeReason
                ) else it
            })
        }
    } == true

    /**
     * Explicitly closes an interrupted CLAIMED item as unresolved, never as acknowledged.
     * Only MASTER/USER may do this, and only while the exact owning run is BLOCKED.
     */
    fun resolveUncertainClaim(
        context: Context,
        taskId: String,
        runId: Int,
        messageId: String,
        actor: String,
        reason: String,
        now: Long = System.currentTimeMillis()
    ): Boolean = MasterTaskQueueStore.withBlockedRun(context, taskId, runId) {
        synchronized(this) {
            if (!valid(taskId) || !valid(messageId) || actor !in setOf("MASTER", "USER") ||
                reason.isBlank() || reason.length > 300 || now <= 0L) return@synchronized false
            val queue = MasterTaskQueueStore.snapshot(context)
            if (queue.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) return@synchronized false
            val task = queue.tasks.firstOrNull { it.id == taskId } ?: return@synchronized false
            val current = read(context)
            if (current.status != ReadStatus.AVAILABLE) return@synchronized false
            val message = current.messages.firstOrNull { it.id == messageId } ?: return@synchronized false
            if (message.taskId != taskId || message.runId != runId ||
                message.recipientAgentId !in task.assignedAgentIds ||
                message.state != State.CLAIMED || message.claimedAt <= 0L ||
                now < message.claimedAt || now < message.createdAt) return@synchronized false
            val safeReason = CommunicationLogSanitizer.sanitize(reason.trim())
            // Redaction can expand a short input. Reject rather than persist a truncated audit reason.
            if (safeReason.isBlank() || safeReason.length > 300) return@synchronized false
            write(context, current.messages.map {
                if (it.id == messageId) it.copy(
                    state = State.MANUALLY_RESOLVED,
                    updatedAt = now,
                    resolvedAt = now,
                    resolutionActor = actor,
                    resolutionReason = safeReason
                ) else it
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
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) {
            return Snapshot(ReadStatus.UNAVAILABLE)
        }
        return try {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, null) ?: return@withHealthyAccess Snapshot(ReadStatus.AVAILABLE)
                if (raw.length > MAX_RAW_CHARS) {
                    return@withHealthyAccess Snapshot(ReadStatus.CORRUPT)
                }
                try {
                    val array = JSONArray(raw)
                    if (array.length() > MAX_MESSAGES) return@withHealthyAccess Snapshot(ReadStatus.CORRUPT)
                    val messages = (0 until array.length()).map { index ->
                        val item = array.getJSONObject(index)
                        require(listOf("id", "taskId", "senderAgentId", "recipientAgentId", "payload", "state")
                            .all { item.opt(it) is String }) { "HANDOFF_STRING_FIELD_TYPE" }
                        fun exactLong(field: String, required: Boolean = true): Long {
                            if (!item.has(field)) {
                                require(!required) { "HANDOFF_MISSING_$field" }
                                return 0L
                            }
                            val value = item.opt(field)
                            require(value is Int || value is Long) { "HANDOFF_NUMBER_FIELD_TYPE_$field" }
                            return (value as Number).toLong()
                        }
                        require(!item.has("resolutionActor") || item.opt("resolutionActor") is String)
                        require(!item.has("resolutionReason") || item.opt("resolutionReason") is String)
                        val parsedRunId = exactLong("runId")
                        require(parsedRunId in 1..Int.MAX_VALUE.toLong())
                        Handoff(
                            item.getString("id"), item.getString("taskId"), parsedRunId.toInt(),
                            item.getString("senderAgentId"), item.getString("recipientAgentId"),
                            item.getString("payload"), State.valueOf(item.getString("state")),
                            exactLong("createdAt"), exactLong("updatedAt"),
                            exactLong("claimedAt", false), exactLong("resolvedAt", false),
                            if (item.has("resolutionActor")) item.getString("resolutionActor") else "",
                            if (item.has("resolutionReason")) item.getString("resolutionReason") else ""
                        )
                    }
                    if (messages.any { !validRecord(it) } ||
                        messages.map { it.id }.distinct().size != messages.size) {
                        Snapshot(ReadStatus.CORRUPT)
                    } else Snapshot(ReadStatus.AVAILABLE, messages)
                } catch (_: Exception) {
                    Snapshot(ReadStatus.CORRUPT)
                }
            }
        } catch (_: IllegalStateException) {
            if (SharedPreferencesWriteHealth.isUncertain(PREFS)) Snapshot(ReadStatus.UNAVAILABLE)
            else Snapshot(ReadStatus.CORRUPT)
        }
    }

    private fun validRecord(it: Handoff): Boolean =
        valid(it.id) && valid(it.taskId) && valid(it.senderAgentId) &&
            valid(it.recipientAgentId) && it.senderAgentId != it.recipientAgentId &&
            it.runId > 0 && it.payload.isNotBlank() && it.payload.length <= MAX_PAYLOAD &&
            CommunicationLogSanitizer.sanitize(it.payload) == it.payload &&
            it.createdAt > 0L && it.updatedAt >= it.createdAt &&
            it.claimedAt >= 0L && it.resolvedAt >= 0L &&
            it.resolutionActor.length <= 100 && it.resolutionReason.length <= 300 &&
            CommunicationLogSanitizer.sanitize(it.resolutionReason) == it.resolutionReason &&
            when (it.state) {
                State.PENDING -> it.claimedAt == 0L && it.updatedAt == it.createdAt &&
                    it.resolvedAt == 0L && it.resolutionActor.isEmpty() && it.resolutionReason.isEmpty()
                State.CLAIMED -> it.claimedAt >= it.createdAt && it.updatedAt == it.claimedAt &&
                    it.resolvedAt == 0L && it.resolutionActor.isEmpty() && it.resolutionReason.isEmpty()
                State.ACKNOWLEDGED -> it.claimedAt >= it.createdAt &&
                    it.updatedAt >= it.claimedAt && it.resolvedAt == 0L &&
                    it.resolutionActor.isEmpty() && it.resolutionReason.isEmpty()
                State.REJECTED -> it.claimedAt >= it.createdAt &&
                    it.updatedAt >= it.claimedAt && it.resolvedAt == 0L &&
                    it.resolutionActor.isEmpty() && it.resolutionReason.isNotBlank()
                State.MANUALLY_RESOLVED -> it.claimedAt >= it.createdAt && it.resolvedAt >= it.claimedAt &&
                    it.resolvedAt >= it.createdAt && it.updatedAt == it.resolvedAt &&
                    it.resolutionActor in setOf("MASTER", "USER") && it.resolutionReason.isNotBlank()
            }

    private fun write(context: Context, messages: List<Handoff>): Boolean {
        if (messages.size > MAX_MESSAGES || messages.map { it.id }.distinct().size != messages.size ||
            messages.any { !validRecord(it) }) return false
        val array = JSONArray()
        messages.forEach { m ->
            array.put(JSONObject()
                .put("id", m.id).put("taskId", m.taskId).put("runId", m.runId)
                .put("senderAgentId", m.senderAgentId).put("recipientAgentId", m.recipientAgentId)
                .put("payload", m.payload).put("state", m.state.name)
                .put("createdAt", m.createdAt).put("updatedAt", m.updatedAt)
                .put("claimedAt", m.claimedAt).put("resolvedAt", m.resolvedAt)
                .put("resolutionActor", m.resolutionActor).put("resolutionReason", m.resolutionReason))
        }
        val serialized = array.toString()
        if (serialized.length > MAX_RAW_CHARS) return false
        return SharedPreferencesWriteHealth.commit(
            PREFS,
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, serialized)
        )
    }
}
