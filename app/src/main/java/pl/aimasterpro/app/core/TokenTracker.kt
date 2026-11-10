package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONObject

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

    /** Corrupt legacy counters must never take down the provider screen or a usage write. */
    private fun readSavedUsage(context: Context, providerId: String): ModelUsage {
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
                inputTokens = json.optLong("input", 0L).coerceAtLeast(0L),
                outputTokens = json.optLong("output", 0L).coerceAtLeast(0L),
                status = status,
                lastStatusMessage = json.optString("msg", "Gotowy").take(240),
                rateLimitTimestamp = json.optLong("ts", 0L).coerceAtLeast(0L)
            )
        }.getOrElse { ModelUsage(providerId) }
    }

    @Synchronized
    fun recordUsage(context: Context, providerId: String, inputPrompt: String, outputReply: String, status: String = "OK", statusMsg: String = "Gotowy") {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val usage = usageMap.getOrPut(providerId) { readSavedUsage(context, providerId) }
        usage.inputTokens = addTokenCounts(usage.inputTokens, (inputPrompt.length / 4).coerceAtLeast(1).toLong())
        usage.outputTokens = addTokenCounts(usage.outputTokens, (outputReply.length / 4).coerceAtLeast(1).toLong())
        usage.status = status.takeIf { it in setOf("OK", "RATE_LIMITED_429", "ERROR") } ?: "ERROR"
        usage.lastStatusMessage = statusMsg.take(240)
        if (usage.status == "RATE_LIMITED_429") usage.rateLimitTimestamp = System.currentTimeMillis()

        val json = JSONObject()
            .put("input", usage.inputTokens)
            .put("output", usage.outputTokens)
            .put("status", usage.status)
            .put("msg", usage.lastStatusMessage)
            .put("ts", usage.rateLimitTimestamp)
        prefs.edit().putString(providerId, json.toString()).apply()
    }

    private fun addTokenCounts(left: Long, right: Long): Long {
        val a = left.coerceAtLeast(0L)
        val b = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
    }

    @Synchronized
    fun getUsage(context: Context, providerId: String): ModelUsage {
        return usageMap.getOrPut(providerId) { readSavedUsage(context, providerId) }
    }

    @Synchronized
    fun resetUsage(context: Context, providerId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(providerId).apply()
        usageMap.remove(providerId)
    }
}
