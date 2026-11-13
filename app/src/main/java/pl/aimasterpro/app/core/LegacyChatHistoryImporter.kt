package pl.aimasterpro.app.core

import org.json.JSONArray
import org.json.JSONException

/**
 * Strict parser for the pre-chats_v2 chat_messages mirror.
 * A null result means the source is malformed; an absent/blank source is a valid empty history.
 */
object LegacyChatHistoryImporter {
    data class Message(val role: String, val text: String)

    private const val MAX_RAW_CHARS = 1_000_000
    private const val MAX_MESSAGES = 5_000
    private const val MAX_ROLE_CHARS = 32
    private const val MAX_MESSAGE_CHARS = 32_000

    fun parse(raw: String?): List<Message>? {
        if (raw == null) return emptyList()
        if (raw.length > MAX_RAW_CHARS) return null
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            if (array.length() > MAX_MESSAGES) return null
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: throw JSONException("Legacy message row is not an object")
                    if (!item.has("role") || !item.has("text")) {
                        throw JSONException("Legacy message row is missing role or text")
                    }
                    val role = item.get("role") as? String
                        ?: throw JSONException("Legacy message role is not a string")
                    val text = item.get("text") as? String
                        ?: throw JSONException("Legacy message text is not a string")
                    if (role.length > MAX_ROLE_CHARS || text.length > MAX_MESSAGE_CHARS) {
                        throw JSONException("Legacy message field exceeds its limit")
                    }
                    add(Message(role, text))
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
