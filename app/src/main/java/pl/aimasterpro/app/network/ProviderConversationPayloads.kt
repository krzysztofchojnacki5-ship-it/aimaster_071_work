package pl.aimasterpro.app.network

import org.json.JSONArray
import org.json.JSONObject

/** Provider-specific JSON role mapping for the same bounded visible conversation. */
internal object ProviderConversationPayloads {
    fun openAi(systemPrompt: String, history: List<ConversationTurn>, currentUserContent: Any): JSONArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", currentUserContent))
        return messages
    }

    fun anthropic(history: List<ConversationTurn>, currentUserContent: Any): JSONArray {
        val messages = JSONArray()
        history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", currentUserContent))
        return messages
    }

    fun geminiContents(history: List<ConversationTurn>, currentUserParts: JSONArray): JSONArray {
        val contents = JSONArray()
        history.forEach { turn ->
            val role = if (turn.role == "assistant") "model" else "user"
            contents.put(
                JSONObject().put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.content)))
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", currentUserParts))
        return contents
    }

    fun geminiNativeRequest(systemPrompt: String, contents: JSONArray): JSONObject {
        val systemInstruction = JSONObject().put(
            "parts", JSONArray().put(JSONObject().put("text", systemPrompt))
        )
        return JSONObject()
            .put("systemInstruction", systemInstruction)
            .put("contents", contents)
    }
}
