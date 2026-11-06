package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale

/** Local, privacy-safe model reliability history. No prompts, responses or API keys are stored. */
object ModelHealthTracker {
    private const val PREFS = "model_health_history_v1"
    private const val KEY = "events"
    private const val MAX_EVENTS = 300

    data class Event(
        val timestamp: Long,
        val providerId: String,
        val host: String,
        val model: String,
        val success: Boolean,
        val category: String,
        val latencyMs: Long
    )

    @Synchronized
    fun record(context: Context, provider: ProviderConfig, success: Boolean, category: String = "", latencyMs: Long = 0L) {
        val host = runCatching { URL(provider.baseUrl).host.lowercase(Locale.ROOT) }.getOrDefault(provider.baseUrl.lowercase(Locale.ROOT))
        val all = load(context).toMutableList()
        all.add(0, Event(System.currentTimeMillis(), provider.parentProviderId.ifBlank { provider.id }, host, provider.model, success, category, latencyMs.coerceAtLeast(0L)))
        val json = JSONArray()
        all.take(MAX_EVENTS).forEach { e ->
            json.put(JSONObject().put("ts", e.timestamp).put("provider", e.providerId).put("host", e.host)
                .put("model", e.model).put("ok", e.success).put("category", e.category).put("latency", e.latencyMs))
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
                Event(o.optLong("ts"), o.optString("provider"), o.optString("host"), o.optString("model"),
                    o.optBoolean("ok"), o.optString("category"), o.optLong("latency"))
            }
        }.getOrDefault(emptyList())
    }

    data class Snapshot(
        val samples: Int,
        val successes: Int,
        val failures: Int,
        val successRate: Double,
        val averageLatencyMs: Long,
        val lastFailureCategory: String,
        val rankingScore: Double
    )

    /** Evidence summary is scoped to exact provider, host and model; no cross-model contamination. */
    fun snapshot(context: Context, config: ProviderConfig, now: Long = System.currentTimeMillis()): Snapshot? {
        val base = config.parentProviderId.ifBlank { config.id }
        val host = runCatching { URL(config.baseUrl).host.lowercase(Locale.ROOT) }.getOrDefault(config.baseUrl.lowercase(Locale.ROOT))
        val matching = load(context).asSequence()
            .filter { now - it.timestamp in 0..(7L * 24 * 60 * 60 * 1000) }
            .filter { it.providerId.equals(base, true) && it.model.equals(config.model, true) && it.host == host }
            .sortedByDescending { it.timestamp }
            .take(30).toList()
        if (matching.isEmpty()) return null
        val successes = matching.count { it.success }
        val failures = matching.size - successes
        val successRate = successes.toDouble() / matching.size
        val averageLatency = matching.filter { it.success && it.latencyMs > 0L }
            .let { rows -> if (rows.isEmpty()) 0L else rows.sumOf { it.latencyMs } / rows.size }
        val recentFailure = matching.firstOrNull { !it.success }?.category.orEmpty()
        val severityPenalty = matching.filterNot { it.success }.sumOf { event ->
            when (event.category.uppercase(Locale.ROOT)) {
                "RATE_LIMIT_QUOTA", "RATE_LIMIT", "HTTP_429" -> 15.0
                "DNS_ERROR", "CONNECT_ERROR" -> 12.0
                "READ_TIMEOUT", "RESPONSE_TIMEOUT", "CONNECT_TIMEOUT" -> 8.0
                else -> 6.0
            }
        }.coerceAtMost(35.0)
        // Beta(2,2) prior prevents one lucky or unlucky response from dominating.
        val smoothedReliability = (successes + 2.0) / (matching.size + 4.0)
        val latencyPenalty = (averageLatency / 60_000.0 * 8.0).coerceIn(0.0, 12.0)
        val score = smoothedReliability * 100.0 - severityPenalty - latencyPenalty
        return Snapshot(matching.size, successes, failures, successRate, averageLatency, recentFailure, score)
    }

    /** Keep the configured primary first unless repeated evidence and a proven backup justify failover. */
    fun rank(context: Context, configs: List<ProviderConfig>): List<ProviderConfig> {
        if (configs.size < 2) return configs
        val primary = configs.first()
        val primaryHealth = snapshot(context, primary) ?: return configs
        // Require at least two independent observations and a backup with an observed success.
        if (primaryHealth.samples < 2) return configs
        val alternatives = configs.drop(1).map { candidate ->
            candidate to snapshot(context, candidate)
        }.sortedByDescending { (_, health) -> health?.rankingScore ?: 50.0 }
        val provenBackup = alternatives.firstOrNull { (_, health) -> health != null && health.successes > 0 }
        val backupScore = provenBackup?.second?.rankingScore ?: Double.NEGATIVE_INFINITY
        return if (provenBackup != null && backupScore >= primaryHealth.rankingScore + 10.0) {
            listOf(provenBackup.first) + alternatives.filterNot { it.first == provenBackup.first }.map { it.first } + primary
        } else listOf(primary) + alternatives.map { it.first }
    }

    fun summary(context: Context): Map<String, List<Event>> = load(context)
        .groupBy { "${it.providerId} / ${it.model}" }
        .mapValues { (_, events) -> events.sortedByDescending { it.timestamp } }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
