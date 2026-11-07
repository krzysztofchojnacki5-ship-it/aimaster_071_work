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
        val createdAt: Long,
        val chatId: String = ""
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
        createdAt: Long = System.currentTimeMillis(),
        chatId: String = ""
    ): Boolean {
        return SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {

        if (SharedPreferencesWriteHealth.isUncertain(PREFS) || taskId.isBlank() || agentId.isBlank() || providerId.isBlank() || model.isBlank() ||
            chatId.length > 200) return@withHealthyAccess false
        val safeReport = CommunicationLogSanitizer.sanitize(report)
        if (safeReport.isBlank() || safeReport.length > MAX_REPORT_CHARS || createdAt < 0L) return@withHealthyAccess false
        val old = read(context) ?: run {
            android.util.Log.e("AIMasterResultArchive", "Nie zapisano raportu: archiwum zawiera uszkodzone dane.")
            return@withHealthyAccess false
        }
        val next = JSONArray()
        next.put(encode(Entry(taskId, agentId, safeReport, providerId, model, createdAt, chatId)))
        for (i in 0 until old.length()) {
            val row = old.getJSONObject(i)
            if (row.optString("taskId") != taskId) next.put(row)
        }
        val trimmed = JSONArray()
        for (i in 0 until minOf(next.length(), MAX_ENTRIES)) trimmed.put(next.getJSONObject(i))
        return@withHealthyAccess runCatching {
            SharedPreferencesWriteHealth.commit(
                PREFS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY, trimmed.toString())
            )
        }.getOrDefault(false)
    
        }
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

    /** Distinguishes an empty readable archive from corrupt or inaccessible recovery data. */
    @Synchronized
    fun isArchiveReadable(context: Context): Boolean = read(context) != null

    @Synchronized
    fun latest(context: Context, limit: Int = MAX_ENTRIES): List<Entry> {
        if (limit <= 0) return emptyList()
        val rows = read(context) ?: return emptyList()
        return (0 until minOf(rows.length(), limit)).mapNotNull { index ->
            rows.optJSONObject(index)?.let(::decode)
        }
    }

    /**
     * Replays archived specialist reports into their owning chat activity timeline after a
     * process restart. A task marker makes the operation idempotent; no model/API request runs.
     * Legacy archive rows without a chat ID remain safely addressable but cannot be replayed.
     */
    @Synchronized
    fun restoreArchivedReports(context: Context): Int {
        // A failed read is not an empty archive: surface it so recovery cannot be reported as a
        // successful no-op while durable reports are actually inaccessible.
        check(isArchiveReadable(context)) {
            "Archiwum raportów zadań jest niedostępne lub uszkodzone; odzyskiwanie przerwano."
        }
        val entries = latest(context).filter { it.chatId.isNotBlank() }
        if (entries.isEmpty()) return 0
        var restored = 0
        val updated = ChatHistoryStore.updateRoot(context) { root ->
            val chats = root.optJSONObject("chats") ?: return@updateRoot false
            // Archive rows are newest-first, while chat activity is an append-only timeline.
            // Replay oldest-first so the timeline stays chronological and trimming keeps newest reports.
            entries.asReversed().forEach { entry ->
                val chat = chats.optJSONObject(entry.chatId) ?: return@forEach
                val rows = chat.optJSONArray("agentActivity") ?: JSONArray()
                val marker = "[TASK_REPORT_ID:${entry.taskId}]"
                val alreadyMarked = (0 until rows.length()).any { index ->
                    rows.optString(index).contains(marker)
                }
                if (alreadyMarked) return@forEach

                // Older app versions logged reports without task IDs. Use that legacy fallback only
                // when this report text belongs to exactly one archived task in this chat. Identical
                // reports from distinct tasks must never suppress one another.
                val sameReportTaskCount = entries.count {
                    it.chatId == entry.chatId && it.report == entry.report
                }
                val hasUnmarkedLegacyCopy = sameReportTaskCount == 1 &&
                    (0 until rows.length()).any { index ->
                        val existing = rows.optString(index)
                        !existing.contains("[TASK_REPORT_ID:") && existing.endsWith(entry.report)
                    }
                if (hasUnmarkedLegacyCopy) return@forEach
                rows.put("📦 Odzyskany raport agenta ${entry.agentId} (${entry.providerId}/${entry.model}) $marker\n${entry.report}")
                while (rows.length() > 150) rows.remove(0)
                chat.put("agentActivity", rows).put("updatedAt", maxOf(chat.optLong("updatedAt"), entry.createdAt))
                restored++
            }
            restored > 0
        }
        if (!updated) {
            throw IllegalStateException("Nie udało się trwale przywrócić raportów z archiwum do historii czatu.")
        }
        return restored
    }
    private fun encode(entry: Entry): JSONObject = JSONObject()
        .put("taskId", entry.taskId)
        .put("agentId", entry.agentId)
        .put("report", entry.report)
        .put("providerId", entry.providerId)
        .put("model", entry.model)
        .put("createdAt", entry.createdAt)
        .put("chatId", entry.chatId)

    private fun decode(row: JSONObject): Entry = Entry(
        row.getString("taskId"),
        row.getString("agentId"),
        row.getString("report"),
        row.getString("providerId"),
        row.getString("model"),
        row.getLong("createdAt"),
        row.optString("chatId")
    )

    /** Corrupt rows are never silently dropped or overwritten with a fresh empty archive. */
    private fun read(context: Context): JSONArray? = runCatching {
        val raw = SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        } ?: return@runCatching JSONArray()
        runCatching {
            val source = JSONArray(raw)
            val seen = hashSetOf<String>()
            for (i in 0 until source.length()) {
                val row = source.getJSONObject(i)
                val createdAtValue = row.opt("createdAt")
                require(row.opt("taskId") is String && row.opt("agentId") is String &&
                    row.opt("report") is String && row.opt("providerId") is String &&
                    row.opt("model") is String &&
                    (!row.has("chatId") || row.opt("chatId") is String) &&
                    (createdAtValue is Int || createdAtValue is Long))
                val taskId = row.getString("taskId")
                val agentId = row.getString("agentId")
                val report = row.getString("report")
                val providerId = row.getString("providerId")
                val model = row.getString("model")
                val createdAt = (createdAtValue as Number).toLong()
                require(taskId.isNotBlank() && seen.add(taskId))
                require(agentId.isNotBlank() && providerId.isNotBlank() && model.isNotBlank())
                require(report.isNotBlank() && report.length <= MAX_REPORT_CHARS)
                require(createdAt >= 0L)
            }
            source
        }.getOrNull()
    }.getOrNull()
}
