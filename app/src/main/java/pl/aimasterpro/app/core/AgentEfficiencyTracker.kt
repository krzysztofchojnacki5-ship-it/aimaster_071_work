package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

/** Measures observable execution efficiency; it does not claim to score answer quality. */
object AgentEfficiencyTracker {
    private const val PREFS = "agent_efficiency_history_v1"
    private const val KEY = "outcomes"
    private const val MAX_ITEMS = 500
    private const val MAX_RAW_CHARS = 1_000_000
    private const val MAX_TASK_CHARS = 160
    private const val MAX_AGENT_CHARS = 100
    private const val MAX_STATUS_CHARS = 40
    private const val MAX_PROVIDER_CHARS = 100
    private const val MAX_MODEL_CHARS = 160
    private const val MAX_ERROR_CHARS = 80

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
        if (outcome.timestamp <= 0L || outcome.taskId.isBlank() || outcome.agentId.isBlank() ||
            outcome.status.isBlank() || outcome.durationMs < 0L || outcome.attempts < 0 ||
            outcome.taskId.length > MAX_TASK_CHARS || outcome.agentId.length > MAX_AGENT_CHARS ||
            outcome.status.length > MAX_STATUS_CHARS || outcome.providerId.length > MAX_PROVIDER_CHARS ||
            outcome.model.length > MAX_MODEL_CHARS || outcome.errorCategory.length > MAX_ERROR_CHARS) {
            android.util.Log.e("AIMasterEfficiency", "Nie zapisano wyniku: nieprawidłowa tożsamość lub liczniki.")
            return
        }
        val all = readStrict(context)?.toMutableList() ?: run {
            android.util.Log.e("AIMasterEfficiency", "Nie zapisano wyniku: historia efektywności jest uszkodzona.")
            return
        }
        val safeOutcome = outcome.copy(
            providerId = CommunicationLogSanitizer.sanitize(outcome.providerId),
            model = CommunicationLogSanitizer.sanitize(outcome.model),
            errorCategory = CommunicationLogSanitizer.sanitize(outcome.errorCategory)
        )
        if (safeOutcome.providerId.length > MAX_PROVIDER_CHARS ||
            safeOutcome.model.length > MAX_MODEL_CHARS ||
            safeOutcome.errorCategory.length > MAX_ERROR_CHARS) {
            android.util.Log.e("AIMasterEfficiency", "Nie zapisano wyniku: oczyszczone metadane przekraczają limity.")
            return
        }
        all.add(0, safeOutcome)
        val json = JSONArray()
        all.take(MAX_ITEMS).forEach { o ->
            json.put(JSONObject().put("ts", o.timestamp).put("task", o.taskId).put("agent", o.agentId)
                .put("status", o.status).put("duration", o.durationMs).put("attempts", o.attempts)
                .put("provider", o.providerId).put("model", o.model).put("error", o.errorCategory))
        }
        val serialized = json.toString()
        if (serialized.length > MAX_RAW_CHARS) {
            android.util.Log.e("AIMasterEfficiency", "Nie zapisano wyniku: historia przekracza obsługiwany rozmiar.")
            return
        }
        val saved = SharedPreferencesWriteHealth.commit(
            PREFS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, serialized)
        )
        if (!saved) android.util.Log.e("AIMasterEfficiency", "Nie udało się trwale zapisać historii efektywności.")
    }

    @Synchronized
    fun load(context: Context): List<Outcome> = readStrict(context) ?: emptyList()

    /** A malformed row invalidates the whole journal; never return a silently partial history. */
    private fun readStrict(context: Context): List<Outcome>? = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return@withHealthyAccess emptyList()
        require(raw.length <= MAX_RAW_CHARS) { "Efficiency history exceeds its supported raw size." }
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
            require(timestamp > 0L && duration >= 0L && attemptsLong in 0..Int.MAX_VALUE.toLong()) {
                "Efficiency history contains negative or out-of-range counters."
            }
            val task = requiredText("task")
            val agent = requiredText("agent")
            val status = requiredText("status")
            val provider = requiredText("provider")
            val model = requiredText("model")
            val error = requiredText("error")
            require(task.isNotBlank() && task.length <= MAX_TASK_CHARS &&
                agent.isNotBlank() && agent.length <= MAX_AGENT_CHARS &&
                status.isNotBlank() && status.length <= MAX_STATUS_CHARS &&
                provider.length <= MAX_PROVIDER_CHARS && model.length <= MAX_MODEL_CHARS &&
                error.length <= MAX_ERROR_CHARS &&
                CommunicationLogSanitizer.sanitize(provider) == provider &&
                CommunicationLogSanitizer.sanitize(model) == model &&
                CommunicationLogSanitizer.sanitize(error) == error) {
                "Efficiency history row is missing task, agent, or status identity."
            }
            Outcome(timestamp, task, agent, status, duration, attemptsLong.toInt(), provider, model, error)
        }
        }
    }.getOrNull()

    data class AgentStats(val agentId: String, val tasks: Int, val completed: Int, val failed: Int, val avgDurationMs: Long, val avgAttempts: Double)

    @Synchronized
    fun clear(context: Context): Boolean = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            SharedPreferencesWriteHealth.commit(
                PREFS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY)
            )
        }
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
        val durationTotal = rows.asSequence().map { BigInteger.valueOf(it.durationMs) }
            .fold(BigInteger.ZERO, BigInteger::add)
        val averageDuration = durationTotal.divide(BigInteger.valueOf(rows.size.toLong())).longValueExact()
        AgentStats(agent, rows.size, rows.count { it.status == "COMPLETED" }, rows.count { it.status != "COMPLETED" },
            averageDuration,
            rows.sumOf { it.attempts.toLong() }.toDouble() / rows.size)
    }.sortedByDescending { it.tasks }
}
