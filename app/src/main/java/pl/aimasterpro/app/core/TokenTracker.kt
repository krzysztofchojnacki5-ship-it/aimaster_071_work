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
        val totalTokens: Long get() = inputTokens + outputTokens
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

    @Synchronized
    fun recordUsage(context: Context, providerId: String, inputPrompt: String, outputReply: String, status: String = "OK", statusMsg: String = "Gotowy") {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val usage = usageMap.getOrPut(providerId) {
            val saved = prefs.getString(providerId, null)
            if (saved != null) {
                val j = JSONObject(saved)
                ModelUsage(
                    providerId = providerId,
                    inputTokens = j.optLong("input", 0),
                    outputTokens = j.optLong("output", 0),
                    status = j.optString("status", "OK"),
                    lastStatusMessage = j.optString("msg", "Gotowy"),
                    rateLimitTimestamp = j.optLong("ts", 0)
                )
            } else {
                ModelUsage(providerId = providerId)
            }
        }
        usage.inputTokens += (inputPrompt.length / 4).coerceAtLeast(1)
        usage.outputTokens += (outputReply.length / 4).coerceAtLeast(1)
        usage.status = status
        usage.lastStatusMessage = statusMsg
        if (status == "RATE_LIMITED_429") {
            usage.rateLimitTimestamp = System.currentTimeMillis()
        }

        val j = JSONObject()
            .put("input", usage.inputTokens)
            .put("output", usage.outputTokens)
            .put("status", usage.status)
            .put("msg", usage.lastStatusMessage)
            .put("ts", usage.rateLimitTimestamp)
        prefs.edit().putString(providerId, j.toString()).apply()
    }

    @Synchronized
    fun getUsage(context: Context, providerId: String): ModelUsage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return usageMap.getOrPut(providerId) {
            val saved = prefs.getString(providerId, null)
            if (saved != null) {
                val j = JSONObject(saved)
                ModelUsage(
                    providerId = providerId,
                    inputTokens = j.optLong("input", 0),
                    outputTokens = j.optLong("output", 0),
                    status = j.optString("status", "OK"),
                    lastStatusMessage = j.optString("msg", "Gotowy"),
                    rateLimitTimestamp = j.optLong("ts", 0)
                )
            } else {
                ModelUsage(providerId = providerId)
            }
        }
    }

    @Synchronized
    fun resetUsage(context: Context, providerId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(providerId).apply()
        usageMap.remove(providerId)
    }
}
