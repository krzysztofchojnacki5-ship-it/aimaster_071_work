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
    fun readRoot(context: Context): JSONObject? = runCatching {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return@runCatching null
        // Preference access itself can fail (not only JSON parsing). Treat that as unreadable,
        // never as a missing first-run document that could later be overwritten.
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return@runCatching JSONObject()
        runCatching {
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
                        val taskIds = chat.optJSONArray("assistantTaskIds")
                            ?: error("Chat history contains invalid assistant task IDs.")
                        for (i in 0 until taskIds.length()) require(taskIds.opt(i) is String) {
                            "Chat history contains a non-text assistant task ID."
                        }
                    }
                    if (chat.has("agentActivity")) {
                        val activity = chat.optJSONArray("agentActivity")
                            ?: error("Chat history contains invalid agent activity.")
                        for (i in 0 until activity.length()) require(activity.opt(i) is String) {
                            "Chat history contains a non-text agent activity row."
                        }
                    }
                }
            }
            if (root.has("currentId")) require(root.opt("currentId") is String) {
                "Chat history has an invalid current chat ID."
            }
            root
        }.getOrNull()
    }.getOrNull()

    @Synchronized
    fun writeRoot(context: Context, root: JSONObject): Boolean {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS)) return false
        // Re-validate before writing so callers cannot persist malformed chat structures.
        val serialized = root.toString()
        val validated = runCatching {
            // Never allow a caller to replace an unreadable existing history with a fresh document.
            if (readRoot(context) == null) return false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val candidate = JSONObject(serialized)
            validate(candidate)
            SharedPreferencesWriteHealth.commit(PREFS, prefs.edit().putString(KEY, serialized))
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
            if (chat.has("assistantTaskIds")) {
                val ids = chat.optJSONArray("assistantTaskIds") ?: error("Invalid assistant task IDs.")
                for (i in 0 until ids.length()) require(ids.opt(i) is String)
            }
            if (chat.has("agentActivity")) {
                val activity = chat.optJSONArray("agentActivity") ?: error("Invalid agent activity.")
                for (i in 0 until activity.length()) require(activity.opt(i) is String)
            }
        }
        if (root.has("currentId")) require(root.opt("currentId") is String)
    }
}
