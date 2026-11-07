package pl.aimasterpro.app.core

import org.json.JSONArray
import org.json.JSONException

/**
 * Strict parser for the pre-chats_v2 chat_messages mirror.
 * A null result means the source is malformed; an absent/blank source is a valid empty history.
 */
object LegacyChatHistoryImporter {
    data class Message(val role: String, val text: String)

    fun parse(raw: String?): List<Message>? {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
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
                    add(Message(role, text))
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
