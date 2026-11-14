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
    private const val MAX_RAW_CHARS = 3_000_000
    private const val MAX_TASK_ID_CHARS = 512
    private const val MAX_AGENT_ID_CHARS = 120
    private const val MAX_PROVIDER_ID_CHARS = 200
    private const val MAX_MODEL_CHARS = 240
    private const val MAX_CHAT_ID_CHARS = 200
    private const val MAX_REPORT_CHARS = 32_000

    /**
     * Stable archive key shared by queue execution and the task workspace UI.
     * The run epoch prevents a retry from displaying a previous attempt's report.
     */
    fun executionTaskId(parentTaskId: String, agentId: String, runId: Int): String {
        require(parentTaskId.isNotBlank() && agentId.isNotBlank() && runId > 0)
        return "q-${parentTaskId.take(42)}-${agentId.take(32)}-r$runId"
    }

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
        return runCatching { SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {

        if (SharedPreferencesWriteHealth.isUncertain(PREFS) ||
            taskId.isBlank() || taskId.length > MAX_TASK_ID_CHARS ||
            agentId.isBlank() || agentId.length > MAX_AGENT_ID_CHARS ||
            providerId.isBlank() || providerId.length > MAX_PROVIDER_ID_CHARS ||
            model.isBlank() || model.length > MAX_MODEL_CHARS ||
            chatId.length > MAX_CHAT_ID_CHARS) return@withHealthyAccess false
        // Provider/model labels are user-controlled configuration metadata. Redact them
        // before persistence because restored archive entries are rendered in chat history.
        val safeAgentId = CommunicationLogSanitizer.sanitize(agentId.trim())
        val safeProviderId = CommunicationLogSanitizer.sanitize(providerId.trim())
        val safeModel = CommunicationLogSanitizer.sanitize(model.trim())
        // Chat IDs are lookup keys, not display text: reject a transformed key rather than
        // silently attaching a report to a different conversation.
        if (safeAgentId.isBlank() || safeAgentId.length > MAX_AGENT_ID_CHARS ||
            safeProviderId.isBlank() || safeProviderId.length > MAX_PROVIDER_ID_CHARS ||
            safeModel.isBlank() || safeModel.length > MAX_MODEL_CHARS ||
            CommunicationLogSanitizer.sanitize(chatId) != chatId) return@withHealthyAccess false
        val safeReport = CommunicationLogSanitizer.sanitize(report)
        if (safeReport.isBlank() || safeReport.length > MAX_REPORT_CHARS || createdAt < 0L) return@withHealthyAccess false
        val old = read(context) ?: run {
            android.util.Log.e("AIMasterResultArchive", "Nie zapisano raportu: archiwum zawiera uszkodzone dane.")
            return@withHealthyAccess false
        }
        val next = JSONArray()
        next.put(encode(Entry(taskId, safeAgentId, safeReport, safeProviderId, safeModel, createdAt, chatId)))
        for (i in 0 until old.length()) {
            val row = old.getJSONObject(i)
            if (row.optString("taskId") != taskId) next.put(row)
        }
        // Never evict an unacknowledged specialist report to make room for a new one.
        // Replacing an existing task remains possible at capacity because it was removed above.
        if (next.length() > MAX_ENTRIES) return@withHealthyAccess false
        val serialized = next.toString()
        if (serialized.length > MAX_RAW_CHARS) return@withHealthyAccess false
        return@withHealthyAccess runCatching {
            SharedPreferencesWriteHealth.commit(
                PREFS, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY, serialized)
            )
        }.getOrDefault(false)
    
        } }.getOrDefault(false)
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
                val safeAgentLabel = CommunicationLogSanitizer.sanitize(entry.agentId).take(MAX_AGENT_ID_CHARS)
                val safeProviderLabel = CommunicationLogSanitizer.sanitize(entry.providerId).take(MAX_PROVIDER_ID_CHARS)
                val safeModelLabel = CommunicationLogSanitizer.sanitize(entry.model).take(MAX_MODEL_CHARS)
                rows.put("📦 Odzyskany raport agenta $safeAgentLabel ($safeProviderLabel/$safeModelLabel) $marker\n${entry.report}")
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
        require(raw.length <= MAX_RAW_CHARS) { "Task result archive exceeds its supported raw size." }
        runCatching {
            val source = JSONArray(raw)
            require(source.length() <= MAX_ENTRIES) { "Task result archive exceeds its capacity." }
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
                val chatId = row.optString("chatId")
                require(taskId.isNotBlank() && taskId.length <= MAX_TASK_ID_CHARS && seen.add(taskId))
                require(agentId.isNotBlank() && agentId.length <= MAX_AGENT_ID_CHARS)
                require(providerId.isNotBlank() && providerId.length <= MAX_PROVIDER_ID_CHARS)
                require(model.isNotBlank() && model.length <= MAX_MODEL_CHARS)
                require(chatId.length <= MAX_CHAT_ID_CHARS)
                require(report.isNotBlank() && report.length <= MAX_REPORT_CHARS)
                require(createdAt >= 0L)
            }
            source
        }.getOrNull()
    }.getOrNull()
}
