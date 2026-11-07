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
        val all = readStrict(context)?.toMutableList() ?: run {
            android.util.Log.e("AIMasterLearning", "Nie zapisano zdarzenia: dziennik operacyjny jest uszkodzony.")
            return
        }
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
        val saved = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, json.toString()).commit()
        }.getOrDefault(false)
        if (!saved) android.util.Log.e("AIMasterLearning", "Nie udało się trwale zapisać dziennika operacyjnego.")
    }

    @Synchronized
    fun load(context: Context): List<Event> = readStrict(context) ?: emptyList()

    /** Corrupt rows are not dropped: writers must preserve the original journal for recovery. */
    private fun readStrict(context: Context): List<Event>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return@runCatching emptyList()
        runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                require(o.opt("ts") is Number && o.opt("duration") is Number &&
                    o.opt("attempts") is Number) { "Learning journal row has invalid numeric fields." }
                // Token totals were added after the first journal schema; absence is a valid legacy row.
                if (o.has("inputTokens")) require(o.opt("inputTokens") is Number)
                if (o.has("outputTokens")) require(o.opt("outputTokens") is Number)
                require(listOf("kind", "agent", "provider", "model", "status", "category").all { o.opt(it) is String }) {
                    "Learning journal row has invalid text fields."
                }
                val duration = o.optLong("duration")
                val attempts = o.optInt("attempts")
                val input = o.optLong("inputTokens")
                val output = o.optLong("outputTokens")
                require(duration >= 0L && attempts >= 0 && input >= 0L && output >= 0L) {
                    "Learning journal row has negative counters."
                }
                Event(o.optLong("ts"), o.optString("kind"), o.optString("agent"), o.optString("provider"),
                    o.optString("model"), o.optString("status"), duration, attempts, o.optString("category"),
                    o.optString("taskId"), input, output)
            }
        }.getOrNull()
    }.getOrNull()

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

    fun clear(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    }.getOrDefault(false)
}
