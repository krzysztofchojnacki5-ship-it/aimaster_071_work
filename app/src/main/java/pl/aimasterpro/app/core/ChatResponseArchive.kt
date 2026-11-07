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
    private const val MAX_RESPONSE_CHARS = 32_000

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
            taskId.isBlank() || chatId.isBlank() || chatId.length > 200 ||
            agentId.isBlank() || providerId.isBlank() || model.isBlank() ||
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
        // Never evict an unacknowledged recovery record. A full archive applies backpressure
        // to new writes until successful replay frees capacity.
        if (next.length() > MAX_ENTRIES) return@withHealthyAccess false
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
        if (acknowledgedTaskIds.isNotEmpty() && !acknowledge(context, acknowledgedTaskIds)) {
            android.util.Log.w("AIMasterChatArchive", "Historia została odzyskana, ale archiwum nie potwierdziło zwolnienia wpisów.")
        }
        return restored
    }

    /** Removes only records proven durable in canonical history; missing chats remain pending. */
    private fun acknowledge(context: Context, taskIds: Set<String>): Boolean = runCatching {
        SharedPreferencesWriteHealth.withHealthyAccess(PREFS) {
            val rows = read(context) ?: return@withHealthyAccess false
            val retained = JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.getString("taskId") !in taskIds) retained.put(row)
            }
            if (retained.length() == rows.length()) return@withHealthyAccess true
            SharedPreferencesWriteHealth.commit(
                PREFS,
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY, retained.toString())
            )
        }
    }.getOrDefault(false)

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
            val source = JSONArray(raw)
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
                require(taskId.isNotBlank() && seen.add(taskId))
                require(chatId.isNotBlank() && chatId.length <= 200 && agentId.isNotBlank())
                require(response.isNotBlank() && response.length <= MAX_RESPONSE_CHARS)
                require(providerId.isNotBlank() && model.isNotBlank())
                require((timestamp as Number).toLong() >= 0L)
            }
            source
        }.getOrNull()
    }.getOrNull()
}
