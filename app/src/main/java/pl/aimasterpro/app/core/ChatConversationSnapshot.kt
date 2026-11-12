package pl.aimasterpro.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Fully parsed, immutable-in-practice view of one persisted conversation.
 * Parsing is all-or-nothing so callers can keep the previous UI state on corrupt data.
 */
data class ChatConversationSnapshot(
    val messages: List<Pair<String, String>>,
    val assistantTaskIds: List<String>,
    val agentActivity: List<String>
) {
    companion object {
        private const val MAX_MESSAGES = 5_000
        private const val MAX_TASK_IDS = 5_000
        private const val MAX_AGENT_ACTIVITY = 150
        private const val MAX_ROLE_CHARS = 32
        private const val MAX_MESSAGE_CHARS = 32_000
        private const val MAX_TASK_ID_CHARS = 512
        private const val MAX_ACTIVITY_CHARS = 32_000

        private fun array(chat: JSONObject?, key: String): JSONArray {
            if (chat == null || !chat.has(key)) return JSONArray()
            return chat.optJSONArray(key) ?: error("Invalid $key array.")
        }

        fun parse(chat: JSONObject?): ChatConversationSnapshot? = runCatching {
            val messageRows = array(chat, "messages")
            require(messageRows.length() <= MAX_MESSAGES)
            val restoredMessages = ArrayList<Pair<String, String>>(messageRows.length())
            for (index in 0 until messageRows.length()) {
                val row = messageRows.optJSONObject(index) ?: error("Invalid message row.")
                require(row.has("role") && row.opt("role") is String)
                require(row.has("text") && row.opt("text") is String)
                require(row.getString("role").length <= MAX_ROLE_CHARS)
                require(row.getString("text").length <= MAX_MESSAGE_CHARS)
                restoredMessages += row.getString("role") to row.getString("text")
            }

            val taskRows = array(chat, "assistantTaskIds")
            require(taskRows.length() <= MAX_TASK_IDS)
            val restoredTaskIds = ArrayList<String>(taskRows.length())
            for (index in 0 until taskRows.length()) {
                val value = taskRows.opt(index)
                require(value is String && value.length <= MAX_TASK_ID_CHARS)
                restoredTaskIds += value
            }

            val activityRows = array(chat, "agentActivity")
            require(activityRows.length() <= MAX_AGENT_ACTIVITY)
            val restoredActivity = ArrayList<String>(activityRows.length())
            for (index in 0 until activityRows.length()) {
                val value = activityRows.opt(index)
                require(value is String && value.length <= MAX_ACTIVITY_CHARS)
                restoredActivity += value
            }

            ChatConversationSnapshot(restoredMessages, restoredTaskIds, restoredActivity)
        }.getOrNull()
    }
}
