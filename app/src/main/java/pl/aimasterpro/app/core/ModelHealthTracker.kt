package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

/** Local, privacy-safe model reliability history. No prompts, responses or API keys are stored. */
object ModelHealthTracker {
    private const val PREFS = "model_health_history_v1"
    private const val KEY = "events"
    private const val MAX_EVENTS = 300
    private const val MAX_RAW_CHARS = 512_000
    private const val MAX_PROVIDER_CHARS = 160
    private const val MAX_HOST_CHARS = 255
    private const val MAX_MODEL_CHARS = 200
    private const val MAX_CATEGORY_CHARS = 80

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
        require(provider.id.isNotBlank() && provider.model.isNotBlank() && latencyMs >= 0L) {
            "Nieprawidłowa tożsamość dostawcy lub opóźnienie zdarzenia kondycji modelu."
        }
        val host = runCatching { URL(provider.baseUrl).host.lowercase(Locale.ROOT) }
            .getOrDefault(provider.baseUrl.trim().lowercase(Locale.ROOT))
        val providerIdentity = provider.parentProviderId.ifBlank { provider.id }
        require(host.isNotBlank() && providerIdentity.isNotBlank()) {
            "Zdarzenie kondycji modelu nie zawiera kompletnej tożsamości."
        }
        // A damaged history is never replaced with a fresh partial journal.
        val all = load(context).toMutableList()
        all.add(0, Event(System.currentTimeMillis(), providerIdentity, host, provider.model, success, category, latencyMs))
        val json = JSONArray()
        all.take(MAX_EVENTS).forEach { e ->
            json.put(JSONObject().put("ts", e.timestamp).put("provider", e.providerId).put("host", e.host)
                .put("model", e.model).put("ok", e.success).put("category", e.category).put("latency", e.latencyMs))
        }
        val serialized = json.toString()
        check(serialized.length <= MAX_RAW_CHARS) { "Historia kondycji przekracza obsługiwany rozmiar." }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, serialized).commit()) {
            "Nie udało się trwale zapisać historii kondycji modeli."
        }
    }

    private fun exactNonNegativeLong(value: Any?, field: String): Long {
        val number = value as? Number
            ?: throw IllegalStateException("Historia kondycji zawiera nieprawidłowe pole: $field.")
        val exact = when (number) {
            is Int -> number.toLong()
            is Long -> number
            is BigDecimal -> number.longValueExact()
            is Double -> BigDecimal.valueOf(number).longValueExact()
            is Float -> BigDecimal(number.toString()).longValueExact()
            else -> BigDecimal(number.toString()).longValueExact()
        }
        require(exact >= 0L) { "Historia kondycji zawiera ujemną wartość liczbową: $field." }
        return exact
    }

    @Synchronized
    fun load(context: Context): List<Event> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            require(raw.length <= MAX_RAW_CHARS) { "Historia kondycji przekracza bezpieczny rozmiar surowy." }
            val arr = JSONArray(raw)
            require(arr.length() <= MAX_EVENTS) { "Historia kondycji przekracza bezpieczny rozmiar." }
            (0 until arr.length()).map { index ->
                val o = arr.opt(index) as? JSONObject
                    ?: throw IllegalStateException("Historia kondycji zawiera wpis, który nie jest obiektem.")
                val provider = o.opt("provider") as? String
                val host = o.opt("host") as? String
                val model = o.opt("model") as? String
                val category = o.opt("category") as? String
                val success = o.opt("ok") as? Boolean
                require(!provider.isNullOrBlank() && provider.length <= MAX_PROVIDER_CHARS &&
                    !host.isNullOrBlank() && host.length <= MAX_HOST_CHARS &&
                    !model.isNullOrBlank() && model.length <= MAX_MODEL_CHARS &&
                    category != null && category.length <= MAX_CATEGORY_CHARS && success != null) {
                    "Historia kondycji zawiera niekompletną tożsamość lub metadane."
                }
                Event(
                    exactNonNegativeLong(o.opt("ts"), "ts"),
                    provider,
                    host,
                    model,
                    success,
                    category,
                    exactNonNegativeLong(o.opt("latency"), "latency")
                )
            }
        } catch (error: Exception) {
            throw IllegalStateException("Historia kondycji modeli jest uszkodzona; odczyt i zapis zostały wstrzymane.", error)
        }
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
            .let { rows ->
                if (rows.isEmpty()) 0L else rows.asSequence().map { BigInteger.valueOf(it.latencyMs) }
                    .fold(BigInteger.ZERO, BigInteger::add)
                    .divide(BigInteger.valueOf(rows.size.toLong())).longValueExact()
            }
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
        val primaryHealth = runCatching { snapshot(context, primary) }.getOrNull() ?: return configs
        // Require at least two independent observations and a backup with an observed success.
        if (primaryHealth.samples < 2) return configs
        val alternatives = configs.drop(1).map { candidate ->
            candidate to runCatching { snapshot(context, candidate) }.getOrNull()
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
