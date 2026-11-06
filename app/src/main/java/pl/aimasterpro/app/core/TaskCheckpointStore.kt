package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent task-state journal for crash diagnosis/recovery. It never stores prompts, answers or keys. */
object TaskCheckpointStore {
    private const val PREFS = "task_checkpoints_v1"
    private const val KEY = "events"
    private const val MAX_EVENTS = 800
    private val processId = java.util.UUID.randomUUID().toString()
    data class Event(val taskId: String, val agentId: String, val status: String, val detail: String,
                     val timestamp: Long, val providerId: String, val model: String, val attempts: Int,
                     val processId: String = "")

    @Synchronized fun record(context: Context, event: Event) {
        if (event.taskId.isBlank()) return
        val rows = load(context).toMutableList()
        rows.add(0, event.copy(agentId = event.agentId.take(100), status = event.status.take(30),
            detail = event.detail.take(500), providerId = event.providerId.take(100), model = event.model.take(160),
            attempts = event.attempts.coerceAtLeast(0), processId = event.processId.ifBlank { processId }))
        save(context, rows.take(MAX_EVENTS))
    }

    @Synchronized fun load(context: Context): List<Event> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("task")
            if (id.isBlank()) null else Event(id, o.optString("agent"), o.optString("status"), o.optString("detail"),
                o.optLong("ts"), o.optString("provider"), o.optString("model"), o.optInt("attempts"), o.optString("processId"))
        }
    }.getOrDefault(emptyList())

    /** At process startup, any RUNNING task is known to have lost its worker process. */
    @Synchronized fun recoverStaleRunning(context: Context, now: Long = System.currentTimeMillis()): Int {
        val latest = load(context).groupBy { it.taskId }.values.mapNotNull { it.maxByOrNull(Event::timestamp) }
        val stale = latest.filter { it.status in setOf("QUEUED", "RUNNING") && it.processId != processId }
        stale.forEach { event -> record(context, event.copy(status = "INTERRUPTED", detail = "Proces aplikacji zakończył się przed zapisaniem wyniku.", timestamp = now, processId = processId)) }
        return stale.size
    }

    fun latest(context: Context): List<Event> = load(context).groupBy { it.taskId }
        .values.mapNotNull { rows -> rows.maxByOrNull(Event::timestamp) }.sortedByDescending { it.timestamp }

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply() }

    private fun save(context: Context, rows: List<Event>) {
        val a = JSONArray()
        rows.forEach { e -> a.put(JSONObject().put("task", e.taskId).put("agent", e.agentId).put("status", e.status)
            .put("detail", e.detail).put("ts", e.timestamp).put("provider", e.providerId).put("model", e.model).put("attempts", e.attempts).put("processId", e.processId)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).commit()
    }
}
