package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable archive of successful specialist reports.
 *
 * This is deliberately separate from TaskResultCache: cache entries are reusable only for an
 * exact provider/model fingerprint and expire, while archived results remain addressable by task ID.
 */
object TaskResultArchive {
    data class Entry(
        val taskId: String,
        val agentId: String,
        val report: String,
        val providerId: String,
        val model: String,
        val createdAt: Long
    )

    private const val PREFS = "task_result_archive_v1"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 80
    private const val MAX_REPORT_CHARS = 32_000

    @Synchronized
    fun put(
        context: Context,
        taskId: String,
        agentId: String,
        report: String,
        providerId: String,
        model: String,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean {
        if (taskId.isBlank() || agentId.isBlank() || providerId.isBlank() || model.isBlank()) return false
        val safeReport = CommunicationLogSanitizer.sanitize(report)
        if (safeReport.isBlank() || safeReport.length > MAX_REPORT_CHARS || createdAt < 0L) return false
        val old = read(context) ?: run {
            android.util.Log.e("AIMasterResultArchive", "Nie zapisano raportu: archiwum zawiera uszkodzone dane.")
            return false
        }
        val next = JSONArray()
        next.put(encode(Entry(taskId, agentId, safeReport, providerId, model, createdAt)))
        for (i in 0 until old.length()) {
            val row = old.getJSONObject(i)
            if (row.optString("taskId") != taskId) next.put(row)
        }
        val trimmed = JSONArray()
        for (i in 0 until minOf(next.length(), MAX_ENTRIES)) trimmed.put(next.getJSONObject(i))
        return runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, trimmed.toString())
                .commit()
        }.getOrDefault(false)
    }

    @Synchronized
    fun get(context: Context, taskId: String): Entry? {
        if (taskId.isBlank()) return null
        val rows = read(context) ?: return null
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: return null
            if (row.optString("taskId") == taskId) return decode(row)
        }
        return null
    }

    @Synchronized
    fun latest(context: Context, limit: Int = MAX_ENTRIES): List<Entry> {
        if (limit <= 0) return emptyList()
        val rows = read(context) ?: return emptyList()
        return (0 until minOf(rows.length(), limit)).mapNotNull { index ->
            rows.optJSONObject(index)?.let(::decode)
        }
    }

    private fun encode(entry: Entry): JSONObject = JSONObject()
        .put("taskId", entry.taskId)
        .put("agentId", entry.agentId)
        .put("report", entry.report)
        .put("providerId", entry.providerId)
        .put("model", entry.model)
        .put("createdAt", entry.createdAt)

    private fun decode(row: JSONObject): Entry = Entry(
        row.getString("taskId"),
        row.getString("agentId"),
        row.getString("report"),
        row.getString("providerId"),
        row.getString("model"),
        row.getLong("createdAt")
    )

    /** Corrupt rows are never silently dropped or overwritten with a fresh empty archive. */
    private fun read(context: Context): JSONArray? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return JSONArray()
        return runCatching {
            val source = JSONArray(raw)
            val seen = hashSetOf<String>()
            for (i in 0 until source.length()) {
                val row = source.getJSONObject(i)
                require(row.opt("taskId") is String && row.opt("agentId") is String &&
                    row.opt("report") is String && row.opt("providerId") is String &&
                    row.opt("model") is String && row.opt("createdAt") is Number)
                val taskId = row.getString("taskId")
                val agentId = row.getString("agentId")
                val report = row.getString("report")
                val providerId = row.getString("providerId")
                val model = row.getString("model")
                val createdAt = row.getLong("createdAt")
                require(taskId.isNotBlank() && seen.add(taskId))
                require(agentId.isNotBlank() && providerId.isNotBlank() && model.isNotBlank())
                require(report.isNotBlank() && report.length <= MAX_REPORT_CHARS)
                require(createdAt >= 0L)
            }
            source
        }.getOrNull()
    }
}
