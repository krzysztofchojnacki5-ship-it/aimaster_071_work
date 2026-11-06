package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict, durable access to the legacy chats_v2 document.
 * A missing document is a valid empty store; malformed or structurally invalid data is not.
 */
object ChatHistoryStore {
    private const val PREFS = "chat_state"
    private const val KEY = "chats_v2"

    @Synchronized
    fun readRoot(context: Context): JSONObject? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return JSONObject()
        return runCatching {
            val root = JSONObject(raw)
            if (root.has("chats")) {
                val chats = root.optJSONObject("chats")
                    ?: error("Chat history has an invalid chats object.")
                val keys = chats.keys()
                while (keys.hasNext()) {
                    val chatId = keys.next()
                    val chat = chats.optJSONObject(chatId)
                        ?: error("Chat history contains a non-object chat.")
                    if (chat.has("messages")) {
                        val messages = chat.optJSONArray("messages")
                            ?: error("Chat history contains an invalid messages array.")
                        for (i in 0 until messages.length()) {
                            val message = messages.optJSONObject(i)
                                ?: error("Chat history contains an invalid message row.")
                            require(message.has("role") && message.has("text")) {
                                "Chat history message is missing role or text."
                            }
                            require(message.opt("role") is String && message.opt("text") is String) {
                                "Chat history message has invalid field types."
                            }
                        }
                    }
                    if (chat.has("assistantTaskIds")) {
                        require(chat.optJSONArray("assistantTaskIds") != null) {
                            "Chat history contains invalid assistant task IDs."
                        }
                    }
                    if (chat.has("agentActivity")) {
                        require(chat.optJSONArray("agentActivity") != null) {
                            "Chat history contains invalid agent activity."
                        }
                    }
                }
            }
            if (root.has("currentId")) require(root.opt("currentId") is String) {
                "Chat history has an invalid current chat ID."
            }
            root
        }.getOrNull()
    }

    @Synchronized
    fun writeRoot(context: Context, root: JSONObject): Boolean {
        // Re-validate before writing so callers cannot persist malformed chat structures.
        val serialized = root.toString()
        val validated = runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val candidate = JSONObject(serialized)
            validate(candidate)
            prefs.edit().putString(KEY, serialized).commit()
        }.getOrDefault(false)
        return validated
    }

    private fun validate(root: JSONObject) {
        if (!root.has("chats")) return
        val chats = root.optJSONObject("chats") ?: error("Invalid chats object.")
        val keys = chats.keys()
        while (keys.hasNext()) {
            val chat = chats.optJSONObject(keys.next()) ?: error("Invalid chat row.")
            if (chat.has("messages")) {
                val rows = chat.optJSONArray("messages") ?: error("Invalid messages array.")
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: error("Invalid message row.")
                    require(row.opt("role") is String && row.opt("text") is String)
                }
            }
            if (chat.has("assistantTaskIds")) require(chat.optJSONArray("assistantTaskIds") != null)
            if (chat.has("agentActivity")) require(chat.optJSONArray("agentActivity") != null)
        }
        if (root.has("currentId")) require(root.opt("currentId") is String)
    }
}
