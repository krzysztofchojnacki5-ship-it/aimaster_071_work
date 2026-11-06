package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Measures observable execution efficiency; it does not claim to score answer quality. */
object AgentEfficiencyTracker {
    private const val PREFS = "agent_efficiency_history_v1"
    private const val KEY = "outcomes"
    private const val MAX_ITEMS = 500

    data class Outcome(
        val timestamp: Long,
        val taskId: String,
        val agentId: String,
        val status: String,
        val durationMs: Long,
        val attempts: Int,
        val providerId: String,
        val model: String,
        val errorCategory: String
    )

    @Synchronized
    fun record(context: Context, outcome: Outcome) {
        val all = load(context).toMutableList()
        all.add(0, outcome)
        val json = JSONArray()
        all.take(MAX_ITEMS).forEach { o ->
            json.put(JSONObject().put("ts", o.timestamp).put("task", o.taskId).put("agent", o.agentId)
                .put("status", o.status).put("duration", o.durationMs).put("attempts", o.attempts)
                .put("provider", o.providerId).put("model", o.model).put("error", o.errorCategory))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }

    @Synchronized
    fun load(context: Context): List<Outcome> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Outcome(o.optLong("ts"), o.optString("task"), o.optString("agent"), o.optString("status"),
                    o.optLong("duration"), o.optInt("attempts"), o.optString("provider"), o.optString("model"), o.optString("error"))
            }
        }.getOrDefault(emptyList())
    }

    data class AgentStats(val agentId: String, val tasks: Int, val completed: Int, val failed: Int, val avgDurationMs: Long, val avgAttempts: Double)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /**
     * Conservative operational reliability used only as a routing tie-breaker.
     * It measures completion, not answer correctness, and requires independent tasks.
     */
    fun reliabilityScore(outcomes: List<Outcome>, agentId: String, minSamples: Int = 8): Double? {
        require(minSamples >= 1)
        val unique = outcomes.asSequence()
            .filter { it.agentId.equals(agentId, true) && it.taskId.isNotBlank() }
            .distinctBy { it.taskId }
            .toList()
        if (unique.size < minSamples) return null
        val completed = unique.count { it.status.equals("COMPLETED", true) }
        return (completed + 2.0) / (unique.size + 4.0)
    }

    fun stats(context: Context): List<AgentStats> = load(context).groupBy { it.agentId }.map { (agent, rows) ->
        AgentStats(agent, rows.size, rows.count { it.status == "COMPLETED" }, rows.count { it.status != "COMPLETED" },
            if (rows.isEmpty()) 0L else rows.sumOf { it.durationMs.coerceAtLeast(0L) } / rows.size,
            if (rows.isEmpty()) 0.0 else rows.sumOf { it.attempts.coerceAtLeast(0) }.toDouble() / rows.size)
    }.sortedByDescending { it.tasks }
}
