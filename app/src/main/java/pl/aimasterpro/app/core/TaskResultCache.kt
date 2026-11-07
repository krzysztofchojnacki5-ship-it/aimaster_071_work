package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Reuses only exact, successful specialist outputs; never caches failures or MASTER answers. */
object TaskResultCache {
    data class Cached(val report: String, val createdAt: Long)
    private const val PREFS = "task_result_cache_v1"
    private const val KEY = "entries"
    private const val TTL_MS = 72L * 60L * 60L * 1000L
    private const val MAX_ENTRIES = 60

    /** Length-prefix every field so embedded separators cannot create ambiguous fingerprints.
     * Provider identity is part of the key: equal model aliases on different APIs are not equivalent.
     */
    fun fingerprint(agentId: String, goal: String, prompt: String, projectContext: String,
                    attachmentMime: String, attachmentBase64: String, providerId: String,
                    baseUrl: String, model: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("task-cache-v2", agentId, goal, prompt, projectContext, attachmentMime,
            attachmentBase64, providerId, baseUrl.trim().trimEnd('/'), model).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun get(context: Context, fingerprint: String, now: Long = System.currentTimeMillis()): Cached? {
        val rows = read(context, now) ?: return null
        val row = (0 until rows.length()).map { rows.optJSONObject(it) }.filterNotNull()
            .firstOrNull { it.optString("fingerprint") == fingerprint } ?: return null
        return Cached(row.optString("report"), row.optLong("createdAt"))
    }

    /**
     * Cache writes use the same per-file transaction lock and ambiguous-commit quarantine as
     * recovery-critical stores. A false commit must never be followed by a read-modify-write
     * that could overwrite the last known durable snapshot.
     */
    @Synchronized fun put(context: Context, fingerprint: String, report: String, now: Long = System.currentTimeMillis()): Boolean =
        runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                if (fingerprint.isBlank() || report.isBlank() || now < 0L) return@withHealthyAccess false
                val safeReport = CommunicationLogSanitizer.sanitize(report)
                if (safeReport.isBlank()) return@withHealthyAccess false
                val old = read(context, now) ?: run {
                    android.util.Log.e("AIMasterTaskCache", "Nie zapisano wyniku: cache zawiera uszkodzone dane.")
                    return@withHealthyAccess false
                }
                val next = JSONArray()
                for (i in 0 until old.length()) {
                    val row = old.getJSONObject(i)
                    if (row.optString("fingerprint") != fingerprint) next.put(row)
                }
                next.put(JSONObject().put("fingerprint", fingerprint).put("report", safeReport).put("createdAt", now))
                val trimmed = JSONArray()
                val start = (next.length() - MAX_ENTRIES).coerceAtLeast(0)
                for (i in start until next.length()) trimmed.put(next.getJSONObject(i))
                SharedPreferencesWriteHealth.commit(
                    PREFS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY, trimmed.toString())
                )
            }
        }.getOrDefault(false)

    /** Corrupt cache rows are not dropped or replaced by an empty cache during a write. */
    private fun read(context: Context, now: Long): JSONArray? = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?: return@withHealthyAccess JSONArray()
            val source = JSONArray(raw)
            val valid = JSONArray()
            val seenFingerprints = hashSetOf<String>()
            for (i in 0 until source.length()) {
                val row = source.getJSONObject(i)
                val fingerprintValue = row.opt("fingerprint")
                val reportValue = row.opt("report")
                val createdAtValue = row.opt("createdAt")
                require(fingerprintValue is String && fingerprintValue.isNotBlank())
                require(seenFingerprints.add(fingerprintValue))
                require(reportValue is String && reportValue.isNotBlank())
                require(createdAtValue is Int || createdAtValue is Long)
                val createdAt = (createdAtValue as Number).toLong()
                require(createdAt >= 0L)
                val age = now - createdAt
                if (age in 0..TTL_MS) valid.put(row)
            }
            valid
        }
    }.getOrNull()
}
