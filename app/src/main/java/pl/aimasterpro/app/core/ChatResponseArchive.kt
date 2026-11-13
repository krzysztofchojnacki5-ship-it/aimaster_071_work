package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable write-ahead archive for successful final chat responses.
 *
 * TaskResultArchive stores specialist reports in agent activity; this store preserves the
 * actual assistant message until it is committed to ChatHistoryStore.
 */
object ChatResponseArchive {
    data class Entry(
        val taskId: String,
        val chatId: String,
        val agentId: String,
        val response: String,
        val providerId: String,
        val model: String,
        val createdAt: Long
    )

    private const val PREFS = "chat_response_archive_v1"
    private const val KEY = "responses"
    private const val MAX_ENTRIES = 25
    private const val MAX_TASK_ID_CHARS = 512
    private const val MAX_AGENT_ID_CHARS = 120
    private const val MAX_PROVIDER_ID_CHARS = 200
    private const val MAX_MODEL_CHARS = 240
    private const val MAX_RESPONSE_CHARS = 32_000
    private const val MAX_RAW_CHARS = 1_000_000

    @Synchronized
    fun put(
        context: Context,
        taskId: String,
        chatId: String,
        agentId: String,
        response: String,
        providerId: String,
        model: String,
        createdAt: Long = System.currentTimeMillis()
    ): Boolean = runCatching { SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS) ||
            taskId.isBlank() || taskId.length > MAX_TASK_ID_CHARS ||
            chatId.isBlank() || chatId.length > 200 ||
            agentId.isBlank() || agentId.length > MAX_AGENT_ID_CHARS ||
            providerId.isBlank() || providerId.length > MAX_PROVIDER_ID_CHARS ||
            model.isBlank() || model.length > MAX_MODEL_CHARS ||
            response.isBlank() || response.length > MAX_RESPONSE_CHARS || createdAt < 0L
        ) return@withHealthyAccess false

        val old = read(context) ?: run {
            android.util.Log.e("AIMasterChatArchive", "Nie zapisano odpowiedzi: archiwum zawiera uszkodzone dane.")
            return@withHealthyAccess false
        }
        val next = JSONArray()
        next.put(encode(Entry(taskId, chatId, agentId, response, providerId, model, createdAt)))
        for (i in 0 until old.length()) {
            val row = old.getJSONObject(i)
            if (row.optString("taskId") != taskId) next.put(row)
        }
        // This is a write-ahead log: capacity pressure must not silently discard an
        // unacknowledged answer. Existing task entries can still be replaced at capacity.
        if (next.length() > MAX_ENTRIES || next.toString().length > MAX_RAW_CHARS) {
            return@withHealthyAccess false
        }
        runCatching {
            SharedPreferencesWriteHealth.commit(
                PREFS,
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY, next.toString())
            )
        }.getOrDefault(false)
    } }.getOrDefault(false)

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
     * Replays successful assistant messages into their original chats. assistantTaskIds is the
     * idempotency ledger, so an answer already committed by the normal UI path is never duplicated.
     */
    @Synchronized
    fun restoreArchivedResponses(context: Context): Int {
        check(isArchiveReadable(context)) {
            "Archiwum odpowiedzi czatu jest niedostępne lub uszkodzone; odzyskiwanie przerwano."
        }
        val entries = latest(context)
        if (entries.isEmpty()) return 0
        var restored = 0
        val acknowledgedTaskIds = linkedSetOf<String>()
        val updated = ChatHistoryStore.updateRoot(context) { root ->
            val chats = root.optJSONObject("chats") ?: return@updateRoot false
            entries.asReversed().forEach { entry ->
                val chat = chats.optJSONObject(entry.chatId) ?: return@forEach
                val messages = chat.optJSONArray("messages") ?: JSONArray()
                val storedIds = chat.optJSONArray("assistantTaskIds") ?: JSONArray()
                val assistantCount = (0 until messages.length()).count {
                    messages.optJSONObject(it)?.optString("role") == "assistant"
                }
                val alignedIds = AssistantTaskIdAlignment.align(
                    (0 until storedIds.length()).map { storedIds.optString(it) },
                    assistantCount
                )
                if (entry.taskId in alignedIds) {
                    acknowledgedTaskIds += entry.taskId
                    return@forEach
                }

                messages.put(JSONObject().put("role", "assistant").put("text", entry.response))
                val nextIds = JSONArray()
                alignedIds.forEach { nextIds.put(it) }
                nextIds.put(entry.taskId)
                chat.put("messages", messages)
                    .put("assistantTaskIds", nextIds)
                    .put("updatedAt", maxOf(chat.optLong("updatedAt"), entry.createdAt))
                acknowledgedTaskIds += entry.taskId
                restored++
            }
            restored > 0
        }
        if (!updated) {
            throw IllegalStateException("Nie udało się trwale odtworzyć odpowiedzi czatu z archiwum.")
        }
        // The archive is a write-ahead log, not a permanent duplicate of committed history.
        // Acknowledge only after the canonical chat snapshot is durable; orphaned entries remain.
        acknowledgedTaskIds.forEach { taskId ->
            if (!acknowledgePersisted(context, taskId)) {
                android.util.Log.w("AIMasterChatArchive", "Odpowiedź $taskId została zapisana w historii, ale nie udało się potwierdzić jej w archiwum.")
            }
        }
        return restored
    }

    /**
     * Removes one write-ahead entry only after its assistant message and task marker are durable
     * in ChatHistoryStore. Repeated acknowledgement is intentionally idempotent.
     */
    @Synchronized
    fun acknowledgePersisted(context: Context, taskId: String): Boolean {
        if (taskId.isBlank() || taskId.length > MAX_TASK_ID_CHARS) return false
        return runCatching {
            // Verify the canonical message marker before touching the write-ahead entry.
            // Read the two stores in separate critical sections to avoid cross-store lock inversion.
            val old = read(context) ?: return@runCatching false
            val archived = (0 until old.length()).asSequence()
                .map { old.getJSONObject(it) }
                .firstOrNull { it.getString("taskId") == taskId }
                ?: return@runCatching true
            val history = ChatHistoryStore.readRoot(context) ?: return@runCatching false
            val chat = history.optJSONObject("chats")?.optJSONObject(archived.getString("chatId"))
                ?: return@runCatching false
            val ids = chat.optJSONArray("assistantTaskIds") ?: return@runCatching false
            if ((0 until ids.length()).none { ids.optString(it) == taskId }) {
                return@runCatching false
            }

            SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
                val current = read(context) ?: return@withHealthyAccess false
                val next = JSONArray()
                var found = false
                for (i in 0 until current.length()) {
                    val row = current.getJSONObject(i)
                    if (row.getString("taskId") == taskId &&
                        row.getString("chatId") == archived.getString("chatId")) {
                        found = true
                    } else {
                        next.put(row)
                    }
                }
                if (!found) return@withHealthyAccess true
                SharedPreferencesWriteHealth.commit(
                    PREFS,
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(KEY, next.toString())
                )
            }
        }.getOrDefault(false)
    }

    private fun encode(entry: Entry): JSONObject = JSONObject()
        .put("taskId", entry.taskId)
        .put("chatId", entry.chatId)
        .put("agentId", entry.agentId)
        .put("response", entry.response)
        .put("providerId", entry.providerId)
        .put("model", entry.model)
        .put("createdAt", entry.createdAt)

    private fun decode(row: JSONObject): Entry = Entry(
        row.getString("taskId"),
        row.getString("chatId"),
        row.getString("agentId"),
        row.getString("response"),
        row.getString("providerId"),
        row.getString("model"),
        row.getLong("createdAt")
    )

    /** Corrupt rows are never silently dropped or overwritten with an empty archive. */
    private fun read(context: Context): JSONArray? = runCatching {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return@runCatching null
        val raw = SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        } ?: return@runCatching JSONArray()
        runCatching {
            require(raw.length <= MAX_RAW_CHARS) { "Response archive exceeds its raw size limit." }
            val source = JSONArray(raw)
            require(source.length() <= MAX_ENTRIES) { "Response archive exceeds its capacity." }
            val seen = hashSetOf<String>()
            for (i in 0 until source.length()) {
                val row = source.getJSONObject(i)
                val timestamp = row.opt("createdAt")
                require(row.opt("taskId") is String && row.opt("chatId") is String &&
                    row.opt("agentId") is String && row.opt("response") is String &&
                    row.opt("providerId") is String && row.opt("model") is String &&
                    (timestamp is Int || timestamp is Long))
                val taskId = row.getString("taskId")
                val chatId = row.getString("chatId")
                val agentId = row.getString("agentId")
                val response = row.getString("response")
                val providerId = row.getString("providerId")
                val model = row.getString("model")
                require(taskId.isNotBlank() && taskId.length <= MAX_TASK_ID_CHARS && seen.add(taskId))
                require(chatId.isNotBlank() && chatId.length <= 200)
                require(agentId.isNotBlank() && agentId.length <= MAX_AGENT_ID_CHARS)
                require(response.isNotBlank() && response.length <= MAX_RESPONSE_CHARS)
                require(providerId.isNotBlank() && providerId.length <= MAX_PROVIDER_ID_CHARS)
                require(model.isNotBlank() && model.length <= MAX_MODEL_CHARS)
                require((timestamp as Number).toLong() >= 0L)
            }
            source
        }.getOrNull()
    }.getOrNull()
}
