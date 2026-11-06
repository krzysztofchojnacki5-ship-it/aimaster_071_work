package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent, privacy-conscious execution journal. It stores operational metadata, never prompts, answers or secrets. */
object LearningJournalStore {
    private const val PREFS = "ai_learning_journal_v1"
    private const val KEY = "events"
    private const val MAX_EVENTS = 500

    data class Event(
        val timestamp: Long,
        val kind: String,
        val agentId: String,
        val providerId: String,
        val model: String,
        val status: String,
        val durationMs: Long,
        val attempts: Int,
        val category: String,
        val taskId: String = "",
        val inputTokens: Long = 0L,
        val outputTokens: Long = 0L
    )

    @Synchronized
    fun record(context: Context, event: Event) {
        val all = load(context).toMutableList()
        all.add(0, event.copy(
            kind = event.kind.take(40), agentId = event.agentId.take(80),
            providerId = event.providerId.take(80), model = event.model.take(120),
            status = event.status.take(40), category = event.category.take(80)
        ))
        val json = JSONArray()
        all.take(MAX_EVENTS).forEach { e ->
            json.put(JSONObject()
                .put("ts", e.timestamp).put("kind", e.kind).put("agent", e.agentId)
                .put("provider", e.providerId).put("model", e.model).put("status", e.status)
                .put("duration", e.durationMs.coerceAtLeast(0L)).put("attempts", e.attempts.coerceAtLeast(0))
                .put("category", e.category).put("taskId", e.taskId)
                .put("inputTokens", e.inputTokens.coerceAtLeast(0L)).put("outputTokens", e.outputTokens.coerceAtLeast(0L)))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized
    fun load(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Event(o.optLong("ts"), o.optString("kind"), o.optString("agent"), o.optString("provider"),
                    o.optString("model"), o.optString("status"), o.optLong("duration"), o.optInt("attempts"), o.optString("category"), o.optString("taskId"),
                    o.optLong("inputTokens"), o.optLong("outputTokens"))
            }
        }.getOrDefault(emptyList())
    }

    fun contextString(context: Context): String {
        val rows = load(context).filter { it.agentId.isNotBlank() && it.kind == "agent" }
            .groupBy { it.agentId }.map { (id, events) ->
                val completed = events.count { it.status.equals("COMPLETED", true) }
                val rate = if (events.isEmpty()) 0 else completed * 100 / events.size
                val avgAttempts = events.sumOf { it.attempts.coerceAtLeast(0) }.toDouble() / events.size.coerceAtLeast(1)
                val avgSeconds = events.sumOf { it.durationMs.coerceAtLeast(0L) } / events.size.coerceAtLeast(1) / 1000
                "$id: $rate% ukończonych przebiegów (${events.size} zapisów), średnio ${avgSeconds}s, ${"%.1f".format(java.util.Locale.US, avgAttempts)} prób"
            }.sorted()
        if (rows.isEmpty()) return ""
        return "HISTORIA WYKONANIA AGENTÓW (metryki operacyjne, nie ocena merytorycznej jakości odpowiedzi):\n" + rows.joinToString("\n")
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
