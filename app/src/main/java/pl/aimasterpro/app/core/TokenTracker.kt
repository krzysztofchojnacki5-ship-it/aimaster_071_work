package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONObject
import java.math.BigDecimal

object TokenTracker {
    private const val PREFS_NAME = "token_tracker_state"

    data class ModelUsage(
        val providerId: String,
        var inputTokens: Long = 0,
        var outputTokens: Long = 0,
        var status: String = "OK", // OK, RATE_LIMITED_429, ERROR
        var lastStatusMessage: String = "Gotowy do pracy",
        var rateLimitTimestamp: Long = 0
    ) {
        val totalTokens: Long get() = if (Long.MAX_VALUE - inputTokens.coerceAtLeast(0L) < outputTokens.coerceAtLeast(0L)) Long.MAX_VALUE else inputTokens.coerceAtLeast(0L) + outputTokens.coerceAtLeast(0L)
        val estimatedLimit: Long = 30_000 // Standard free tier session soft estimate
        val usagePercent: Int get() = ((totalTokens.toFloat() / estimatedLimit) * 100).toInt().coerceIn(0, 100)

        fun resetCountdownSeconds(): Long {
            if (status != "RATE_LIMITED_429") return 0
            val elapsed = (System.currentTimeMillis() - rateLimitTimestamp) / 1000
            val resetWindow = 60L // 60 seconds reset window estimate
            return (resetWindow - elapsed).coerceAtLeast(0)
        }
    }

    private val usageMap = mutableMapOf<String, ModelUsage>()

    private fun validateProviderId(providerId: String) {
        require(providerId.isNotBlank() && providerId.length <= 160 && providerId == providerId.trim() && providerId.none { it.isISOControl() }) {
            "Nieprawidłowy identyfikator dostawcy."
        }
    }

    private fun exactNonNegativeLong(json: JSONObject, key: String): Long {
        val value = json.opt(key) as? Number ?: throw IllegalStateException("Nieprawidłowe pole licznika: $key.")
        val exact = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is BigDecimal -> value.longValueExact()
            is Double -> BigDecimal.valueOf(value).longValueExact()
            is Float -> BigDecimal(value.toString()).longValueExact()
            else -> BigDecimal(value.toString()).longValueExact()
        }
        // Normalize a negative legacy field independently; do not discard valid sibling counters.
        return exact.coerceAtLeast(0L)
    }

    /** Corrupt legacy counters must never take down the provider screen or a usage write. */
    private fun readSavedUsage(context: Context, providerId: String): ModelUsage {
        validateProviderId(providerId)
        check(!SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) { "Rejestr tokenów jest w kwarantannie po niejednoznacznym zapisie." }
        val raw = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(providerId, null)
        }.getOrNull() ?: return ModelUsage(providerId)
        return runCatching {
            val json = JSONObject(raw)
            val status = json.optString("status", "OK").takeIf {
                it in setOf("OK", "RATE_LIMITED_429", "ERROR")
            } ?: "OK"
            ModelUsage(
                providerId = providerId,
                inputTokens = exactNonNegativeLong(json, "input"),
                outputTokens = exactNonNegativeLong(json, "output"),
                status = status,
                lastStatusMessage = CommunicationLogSanitizer.sanitize(json.optString("msg", "Gotowy")).take(240),
                rateLimitTimestamp = (if (json.has("ts")) exactNonNegativeLong(json, "ts") else 0L).also { timestamp ->
                    require(timestamp <= System.currentTimeMillis() + 300_000L) { "Znacznik czasu limitu dostawcy znajduje się w przyszłości." }
                }
            )
        }.getOrElse { ModelUsage(providerId) }
    }

    @Synchronized
    fun recordUsage(context: Context, providerId: String, inputPrompt: String, outputReply: String, status: String = "OK", statusMsg: String = "Gotowy") {
        validateProviderId(providerId)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = usageMap[providerId] ?: readSavedUsage(context, providerId)
        val nextStatus = status.takeIf { it in setOf("OK", "RATE_LIMITED_429", "ERROR") } ?: "ERROR"
        val next = current.copy(
            inputTokens = addTokenCounts(current.inputTokens, (inputPrompt.length / 4).coerceAtLeast(1).toLong()),
            outputTokens = addTokenCounts(current.outputTokens, (outputReply.length / 4).coerceAtLeast(1).toLong()),
            status = nextStatus,
            lastStatusMessage = CommunicationLogSanitizer.sanitize(statusMsg).take(240),
            rateLimitTimestamp = if (nextStatus == "RATE_LIMITED_429") System.currentTimeMillis() else current.rateLimitTimestamp
        )
        val json = JSONObject().put("input", next.inputTokens).put("output", next.outputTokens)
            .put("status", next.status).put("msg", next.lastStatusMessage).put("ts", next.rateLimitTimestamp)
        check(SharedPreferencesWriteHealth.commit(PREFS_NAME, prefs.edit().putString(providerId, json.toString()))) {
            "Nie udało się trwale zapisać liczników tokenów; rejestr został zabezpieczony."
        }
        usageMap[providerId] = next
    }

    private fun addTokenCounts(left: Long, right: Long): Long {
        val a = left.coerceAtLeast(0L)
        val b = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }

    @Synchronized
    fun getUsage(context: Context, providerId: String): ModelUsage {
        validateProviderId(providerId)
        check(!SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) { "Rejestr tokenów jest w kwarantannie po niejednoznacznym zapisie." }
        return usageMap.getOrPut(providerId) { readSavedUsage(context, providerId) }
    }

    @Synchronized
    fun resetUsage(context: Context, providerId: String) {
        validateProviderId(providerId)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(SharedPreferencesWriteHealth.commit(PREFS_NAME, prefs.edit().remove(providerId))) {
            "Nie udało się trwale wyzerować liczników tokenów."
        }
        usageMap.remove(providerId)
    }
}
