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
        val processId: String = ""
    )

    /**
     * Returns false when SharedPreferences could not confirm a durable write.
     * Events are prepended, so journal order—not wall-clock precision—defines the latest state.
     */
    @Synchronized
    fun record(context: Context, event: Event): Boolean {
        if (event.taskId.isBlank()) return false
        val rows = load(context).toMutableList()
        // A late worker callback must not overwrite a terminal result already recorded by
        // the coordinator (for example TIMED_OUT followed by the worker's CANCELLED callback).
        val current = rows.firstOrNull { it.taskId == event.taskId }
        if (current?.status in TERMINAL_STATUSES) return true
        rows.add(
            0,
            event.copy(
                agentId = event.agentId.take(100),
                status = event.status.take(30),
                detail = event.detail.take(500),
                providerId = event.providerId.take(100),
                model = event.model.take(160),
                attempts = event.attempts.coerceAtLeast(0),
                processId = event.processId.ifBlank { processId }
            )
        )
        return save(context, rows.take(MAX_EVENTS))
    }

    @Synchronized
    fun load(context: Context): List<Event> = runCatching {
        val a = JSONArray(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        )
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("task")
            if (id.isBlank()) null else Event(
                id,
                o.optString("agent"),
                o.optString("status"),
                o.optString("detail"),
                o.optLong("ts"),
                o.optString("provider"),
                o.optString("model"),
                o.optInt("attempts"),
                o.optString("processId")
            )
        }
    }.getOrDefault(emptyList())

    /** At process startup, queued/running work from another process cannot be resumed safely. */
    @Synchronized
    fun recoverStaleRunning(context: Context, now: Long = System.currentTimeMillis()): Int {
        val stale = latest(context).filter {
            it.status in setOf("QUEUED", "RUNNING") && it.processId != processId
        }
        var recovered = 0
        stale.forEach { event ->
            val saved = record(
                context,
                event.copy(
                    status = "INTERRUPTED",
                    detail = "Proces aplikacji zakończył się przed zapisaniem wyniku.",
                    timestamp = now,
                    processId = processId
                )
            )
            if (saved) {
                recovered++
            } else {
                android.util.Log.e("AIMasterRecovery", "Nie udało się trwale zapisać stanu INTERRUPTED dla zadania.")
            }
        }
        return recovered
    }

    /** The journal is newest-first; first occurrence is authoritative even if timestamps collide. */
    @Synchronized
    fun latest(context: Context): List<Event> {
        val seen = hashSetOf<String>()
        return load(context).filter { seen.add(it.taskId) }
            .sortedByDescending { it.timestamp }
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
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, a.toString())
            .commit()
    }.getOrDefault(false)
}
