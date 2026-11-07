package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent task-state journal for crash diagnosis/recovery. It never stores prompts, answers or keys. */
object TaskCheckpointStore {
    private const val PREFS = "task_checkpoints_v1"
    private const val KEY = "events"
    private const val MAX_EVENTS = 800
    private val TERMINAL_STATUSES = setOf("COMPLETED", "FAILED", "BLOCKED", "TIMED_OUT", "CANCELLED", "INTERRUPTED")
    private val processId = java.util.UUID.randomUUID().toString()

    data class Event(
        val taskId: String,
        val agentId: String,
        val status: String,
        val detail: String,
        val timestamp: Long,
        val providerId: String,
        val model: String,
        val attempts: Int,
        val processId: String = "",
        val estimatedInputTokens: Long = 0L,
        val estimatedOutputTokens: Long = 0L,
        val idempotencyKey: String = "",
        val baseUrl: String = ""
    )

    /**
     * Returns false when SharedPreferences could not confirm a durable write.
     * Events are prepended, so journal order—not wall-clock precision—defines the latest state.
     */
    @Synchronized
    fun record(context: Context, event: Event): Boolean {
        if (event.taskId.isBlank()) return false
        val rows = readEvents(context)?.toMutableList() ?: return false
        // A late worker callback must not overwrite a terminal result already recorded by
        // the coordinator (for example TIMED_OUT followed by the worker's CANCELLED callback).
        val current = rows.firstOrNull { it.taskId == event.taskId }
        if (current?.status?.let { it in TERMINAL_STATUSES } == true) return true
        // Later lifecycle states often omit request-only metadata. Preserve the latest
        // REQUESTING details so a USAGE_PENDING checkpoint can still be reconciled after restart.
        rows.add(
            0,
            event.copy(
                agentId = event.agentId.take(100),
                status = event.status.take(30),
                detail = event.detail.take(500),
                providerId = event.providerId.ifBlank { current?.providerId.orEmpty() }.take(100),
                model = event.model.ifBlank { current?.model.orEmpty() }.take(160),
                attempts = event.attempts.coerceAtLeast(0),
                processId = event.processId.ifBlank { processId },
                estimatedInputTokens = event.estimatedInputTokens.coerceAtLeast(0L)
                    .takeIf { it > 0L } ?: current?.estimatedInputTokens ?: 0L,
                estimatedOutputTokens = event.estimatedOutputTokens.coerceAtLeast(0L)
                    .takeIf { it > 0L } ?: current?.estimatedOutputTokens ?: 0L,
                idempotencyKey = event.idempotencyKey.ifBlank { current?.idempotencyKey.orEmpty() }.take(240),
                baseUrl = event.baseUrl.ifBlank { current?.baseUrl.orEmpty() }.take(300)
            )
        )
        return save(context, rows.take(MAX_EVENTS))
    }

    @Synchronized
    fun load(context: Context): List<Event> = readEvents(context) ?: emptyList()

    /**
     * A corrupt journal is distinct from an empty journal. Callers that append must not
     * replace unreadable persisted data with a new empty history.
     */
    private fun readEvents(context: Context): List<Event>? = runCatching {
        val a = JSONArray(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        )
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val id = o.optString("task")
            require(id.isNotBlank()) { "Checkpoint row has no task id." }
            val estimatedInput = o.optLong("estimatedInputTokens")
            val estimatedOutput = o.optLong("estimatedOutputTokens")
            val attempts = o.optInt("attempts")
            require(estimatedInput >= 0L && estimatedOutput >= 0L && attempts >= 0) {
                "Checkpoint row has invalid token estimates or attempt count."
            }
            Event(
                id,
                o.optString("agent"),
                o.optString("status"),
                o.optString("detail"),
                o.optLong("ts"),
                o.optString("provider"),
                o.optString("model"),
                attempts,
                o.optString("processId"),
                estimatedInput,
                estimatedOutput,
                o.optString("idempotencyKey"),
                o.optString("baseUrl")
            )
        }
    }.getOrNull()

    /** At process startup, queued/running work from another process cannot be resumed safely. */
    @Synchronized
    fun recoverStaleRunning(
        context: Context,
        now: Long = System.currentTimeMillis(),
        onAmbiguousRequest: (Event) -> Boolean = { true }
    ): Int {
        val stale = latest(context).filter { event ->
            (event.status in setOf("QUEUED", "RUNNING", "REQUESTING", "USAGE_RECORDED", "USAGE_PENDING", "RESULT_PERSIST_PENDING") && event.processId != processId) ||
                event.status == "INTERRUPTED_USAGE_PENDING"
        }
        var recovered = 0
        stale.forEach { event ->
            val needsUsageRecovery = event.status in setOf("REQUESTING", "USAGE_PENDING", "INTERRUPTED_USAGE_PENDING")
            val usageRecovered = !needsUsageRecovery || runCatching { onAmbiguousRequest(event) }.getOrDefault(false)
            val nextStatus = when {
                needsUsageRecovery && !usageRecovered -> "INTERRUPTED_USAGE_PENDING"
                else -> "INTERRUPTED"
            }
            val detail = when {
                needsUsageRecovery && usageRecovered -> "Proces zakończył się po rozpoczęciu żądania; nieznane zużycie API zabezpieczono jako UNAVAILABLE."
                needsUsageRecovery -> "Proces zakończył się po rozpoczęciu żądania; księgowanie UNAVAILABLE oczekuje na ponowienie."
                event.status == "RESULT_PERSIST_PENDING" -> "Proces zakończył się po odebraniu raportu; trwały zapis wyniku nie został potwierdzony."
                else -> "Proces aplikacji zakończył się przed zapisaniem wyniku."
            }
            val saved = record(context, event.copy(
                status = nextStatus, detail = detail, timestamp = now, processId = processId
            ))
            if (saved) recovered++ else {
                android.util.Log.e("AIMasterRecovery", "Nie udało się trwale zapisać stanu odzyskiwania zadania.")
            }
        }
        return recovered
    }

    /** The journal is newest-first; first occurrence is authoritative regardless of wall-clock changes. */
    @Synchronized
    fun latest(context: Context): List<Event> {
        val seen = hashSetOf<String>()
        return load(context).filter { seen.add(it.taskId) }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun save(context: Context, rows: List<Event>): Boolean = runCatching {
        val a = JSONArray()
        rows.forEach { e ->
            a.put(
                JSONObject()
                    .put("task", e.taskId)
                    .put("agent", e.agentId)
                    .put("status", e.status)
                    .put("detail", e.detail)
                    .put("ts", e.timestamp)
                    .put("provider", e.providerId)
                    .put("model", e.model)
                    .put("attempts", e.attempts)
                    .put("processId", e.processId)
                    .put("estimatedInputTokens", e.estimatedInputTokens)
                    .put("estimatedOutputTokens", e.estimatedOutputTokens)
                    .put("idempotencyKey", e.idempotencyKey)
                    .put("baseUrl", e.baseUrl)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, a.toString())
            .commit()
    }.getOrDefault(false)
}
