package pl.aimasterpro.app.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConversationPayloadsTest {
    private val history = listOf(
        ConversationTurn("user", "Pytanie 1"),
        ConversationTurn("assistant", "Odpowiedź 1")
    )

    @Test fun openAiKeepsSystemFirstThenOrderedHistoryAndCurrentUser() {
        val messages = ProviderConversationPayloads.openAi("instrukcja", history, "Pytanie 2")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("instrukcja", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("Pytanie 1", messages.getJSONObject(1).getString("content"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("Odpowiedź 1", messages.getJSONObject(2).getString("content"))
        assertEquals("Pytanie 2", messages.getJSONObject(3).getString("content"))
    }

    @Test fun anthropicKeepsSystemSeparateAndAppendsCurrentUserContent() {
        val current = JSONArray().put(JSONObject().put("type", "text").put("text", "Pytanie 2"))
        val messages = ProviderConversationPayloads.anthropic(history, current)
        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertTrue(messages.getJSONObject(2).get("content") is JSONArray)
    }

    @Test fun geminiMapsAssistantToModelAndKeepsSystemInstructionSeparate() {
        val currentParts = JSONArray().put(JSONObject().put("text", "Pytanie 2"))
        val contents = ProviderConversationPayloads.geminiContents(history, currentParts)
        val request = ProviderConversationPayloads.geminiNativeRequest("instrukcja", contents)
        assertEquals("instrukcja", request.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text"))
        val turns = request.getJSONArray("contents")
        assertEquals("user", turns.getJSONObject(0).getString("role"))
        assertEquals("model", turns.getJSONObject(1).getString("role"))
        assertEquals("user", turns.getJSONObject(2).getString("role"))
        assertEquals("Pytanie 2", turns.getJSONObject(2).getJSONArray("parts")
            .getJSONObject(0).getString("text"))
    }

    @Test fun attachmentPartsAreOnlyOnTheCurrentGeminiTurn() {
        val image = JSONObject().put("inlineData", JSONObject()
            .put("mimeType", "image/png").put("data", "BASE64"))
        val currentParts = JSONArray().put(JSONObject().put("text", "sprawdź obraz")).put(image)
        val contents = ProviderConversationPayloads.geminiContents(history, currentParts)
        assertFalse(contents.getJSONObject(0).getJSONArray("parts").toString().contains("BASE64"))
        assertFalse(contents.getJSONObject(1).getJSONArray("parts").toString().contains("BASE64"))
        assertTrue(contents.getJSONObject(2).getJSONArray("parts").toString().contains("BASE64"))
    }
}
