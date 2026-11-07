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
        private fun array(chat: JSONObject?, key: String): JSONArray {
            if (chat == null || !chat.has(key)) return JSONArray()
            return chat.optJSONArray(key) ?: error("Invalid $key array.")
        }

        fun parse(chat: JSONObject?): ChatConversationSnapshot? = runCatching {
            val messageRows = array(chat, "messages")
            val restoredMessages = ArrayList<Pair<String, String>>(messageRows.length())
            for (index in 0 until messageRows.length()) {
                val row = messageRows.optJSONObject(index) ?: error("Invalid message row.")
                require(row.has("role") && row.opt("role") is String)
                require(row.has("text") && row.opt("text") is String)
                restoredMessages += row.getString("role") to row.getString("text")
            }

            val taskRows = array(chat, "assistantTaskIds")
            val restoredTaskIds = ArrayList<String>(taskRows.length())
            for (index in 0 until taskRows.length()) {
                val value = taskRows.opt(index)
                require(value is String)
                restoredTaskIds += value
            }

            val activityRows = array(chat, "agentActivity")
            val restoredActivity = ArrayList<String>(activityRows.length())
            for (index in 0 until activityRows.length()) {
                val value = activityRows.opt(index)
                require(value is String)
                restoredActivity += value
            }

            ChatConversationSnapshot(restoredMessages, restoredTaskIds, restoredActivity)
        }.getOrNull()
    }
}
