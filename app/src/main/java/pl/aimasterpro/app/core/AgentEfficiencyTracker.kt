package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

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
        if (outcome.timestamp < 0L || outcome.taskId.isBlank() || outcome.agentId.isBlank() ||
            outcome.status.isBlank() || outcome.durationMs < 0L || outcome.attempts < 0) {
            android.util.Log.e("AIMasterEfficiency", "Nie zapisano wyniku: nieprawidłowa tożsamość lub liczniki.")
            return
        }
        val all = readStrict(context)?.toMutableList() ?: run {
            android.util.Log.e("AIMasterEfficiency", "Nie zapisano wyniku: historia efektywności jest uszkodzona.")
            return
        }
        all.add(0, outcome.copy(
            taskId = outcome.taskId.take(160), agentId = outcome.agentId.take(100),
            status = outcome.status.take(40), providerId = outcome.providerId.take(100),
            model = outcome.model.take(160), errorCategory = outcome.errorCategory.take(80)
        ))
        val json = JSONArray()
        all.take(MAX_ITEMS).forEach { o ->
            json.put(JSONObject().put("ts", o.timestamp).put("task", o.taskId).put("agent", o.agentId)
                .put("status", o.status).put("duration", o.durationMs).put("attempts", o.attempts)
                .put("provider", o.providerId).put("model", o.model).put("error", o.errorCategory))
        }
        val saved = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, json.toString()).commit()
        }.getOrDefault(false)
        if (!saved) android.util.Log.e("AIMasterEfficiency", "Nie udało się trwale zapisać historii efektywności.")
    }

    @Synchronized
    fun load(context: Context): List<Outcome> = readStrict(context) ?: emptyList()

    /** A malformed row invalidates the whole journal; never return a silently partial history. */
    private fun readStrict(context: Context): List<Outcome>? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return@runCatching emptyList()
        val arr = JSONArray(raw)
        require(arr.length() <= MAX_ITEMS) { "Efficiency history exceeds its supported size." }
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun requiredText(name: String): String {
                val value = o.opt(name)
                require(value is String) { "Efficiency history text field is invalid: $name." }
                return value
            }
            fun exactLong(name: String): Long {
                val value = o.opt(name)
                require(value is Int || value is Long || value is Double || value is Float) {
                    "Efficiency history numeric field is invalid: $name."
                }
                val number = value as Number
                return if (value is Int || value is Long) number.toLong()
                else BigDecimal.valueOf(number.toDouble()).longValueExact()
            }
            val timestamp = exactLong("ts")
            val duration = exactLong("duration")
            val attemptsLong = exactLong("attempts")
            require(timestamp >= 0L && duration >= 0L && attemptsLong in 0..Int.MAX_VALUE.toLong()) {
                "Efficiency history contains negative or out-of-range counters."
            }
            val task = requiredText("task")
            val agent = requiredText("agent")
            val status = requiredText("status")
            val provider = requiredText("provider")
            val model = requiredText("model")
            val error = requiredText("error")
            require(task.isNotBlank() && agent.isNotBlank() && status.isNotBlank()) {
                "Efficiency history row is missing task, agent, or status identity."
            }
            Outcome(timestamp, task, agent, status, duration, attemptsLong.toInt(), provider, model, error)
        }
    }.getOrNull()

    data class AgentStats(val agentId: String, val tasks: Int, val completed: Int, val failed: Int, val avgDurationMs: Long, val avgAttempts: Double)

    @Synchronized
    fun clear(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
    }.getOrDefault(false)

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
            rows.sumOf { it.durationMs } / rows.size,
            rows.sumOf { it.attempts }.toDouble() / rows.size)
    }.sortedByDescending { it.tasks }
}
