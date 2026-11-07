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
        fun parse(chat: JSONObject?): ChatConversationSnapshot? = runCatching {
            val messageRows = chat?.optJSONArray("messages") ?: JSONArray()
            val restoredMessages = ArrayList<Pair<String, String>>(messageRows.length())
            for (index in 0 until messageRows.length()) {
                val row = messageRows.optJSONObject(index) ?: error("Invalid message row.")
                require(row.has("role") && row.opt("role") is String)
                require(row.has("text") && row.opt("text") is String)
                restoredMessages += row.getString("role") to row.getString("text")
            }

            val taskRows = chat?.optJSONArray("assistantTaskIds") ?: JSONArray()
            val restoredTaskIds = ArrayList<String>(taskRows.length())
            for (index in 0 until taskRows.length()) {
                val value = taskRows.opt(index)
                require(value is String)
                restoredTaskIds += value
            }

            val activityRows = chat?.optJSONArray("agentActivity") ?: JSONArray()
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
