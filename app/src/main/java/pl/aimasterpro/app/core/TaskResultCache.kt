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

    @Synchronized fun put(context: Context, fingerprint: String, report: String, now: Long = System.currentTimeMillis()): Boolean {
        if (fingerprint.isBlank() || report.isBlank()) return false
        val old = read(context, now) ?: run {
            android.util.Log.e("AIMasterTaskCache", "Nie zapisano wyniku: cache zawiera uszkodzone dane.")
            return false
        }
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val row = old.getJSONObject(i)
            if (row.optString("fingerprint") != fingerprint) next.put(row)
        }
        next.put(JSONObject().put("fingerprint", fingerprint).put("report", report).put("createdAt", now))
        val trimmed = JSONArray()
        val start = (next.length() - MAX_ENTRIES).coerceAtLeast(0)
        for (i in start until next.length()) trimmed.put(next.getJSONObject(i))
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, trimmed.toString()).commit()
    }

    /** Corrupt cache rows are not dropped or replaced by an empty cache during a write. */
    private fun read(context: Context, now: Long): JSONArray? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return JSONArray()
        return runCatching {
            val source = JSONArray(raw)
            val valid = JSONArray()
            for (i in 0 until source.length()) {
                val row = source.getJSONObject(i)
                require(row.opt("fingerprint") is String && row.optString("fingerprint").isNotBlank())
                require(row.opt("report") is String && row.optString("report").isNotBlank())
                require(row.opt("createdAt") is Number)
                val age = now - row.optLong("createdAt")
                if (age in 0..TTL_MS) valid.put(row)
            }
            valid
        }.getOrNull()
    }
}
