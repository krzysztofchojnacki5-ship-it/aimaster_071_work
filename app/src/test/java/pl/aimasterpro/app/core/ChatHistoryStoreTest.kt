package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatHistoryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
    }

    @Test fun missingHistoryIsAValidEmptyStoreAndCanBeCommitted() {
        val root = ChatHistoryStore.readRoot(context)!!
        val chats = JSONObject().put("chat-1", JSONObject()
            .put("title", "Test")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "Hello"))))
        root.put("currentId", "chat-1").put("chats", chats)
        assertTrue(ChatHistoryStore.writeRoot(context, root))
        val restored = ChatHistoryStore.readRoot(context)!!
        assertEquals("chat-1", restored.optString("currentId"))
        assertEquals("Hello", restored.getJSONObject("chats").getJSONObject("chat-1")
            .getJSONArray("messages").getJSONObject(0).getString("text"))
    }

    @Test fun malformedRootIsNotInterpretedAsEmptyOrOverwritten() {
        val corrupt = """{"chats":{"broken":"""
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        assertNull(ChatHistoryStore.readRoot(context))
        assertEquals(corrupt, prefs.getString("chats_v2", null))
    }

    @Test fun malformedNestedMessageBlocksHistoryRead() {
        val corrupt = JSONObject().put("chats", JSONObject().put("chat-1", JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user"))))).toString()
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        assertNull(ChatHistoryStore.readRoot(context))
        assertEquals(corrupt, prefs.getString("chats_v2", null))
    }

    @Test fun malformedChatRowBlocksHistoryRead() {
        val corrupt = JSONObject().put("chats", JSONObject().put("chat-1", "not-an-object")).toString()
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        assertNull(ChatHistoryStore.readRoot(context))
    }

    @Test fun legacyMessagesAndTaskAlignmentMetadataRemainReadable() {
        val legacy = JSONObject().put("currentId", "chat-legacy")
            .put("chats", JSONObject().put("chat-legacy", JSONObject()
                .put("title", "Legacy")
                .put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("text", "Answer")))
                .put("assistantTaskIds", JSONArray().put("task-1"))
                .put("agentActivity", JSONArray().put("Agent started")))).toString()
        assertTrue(prefs.edit().putString("chats_v2", legacy).commit())
        val chat = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats").getJSONObject("chat-legacy")
        assertEquals("task-1", chat.getJSONArray("assistantTaskIds").getString(0))
        assertEquals("Agent started", chat.getJSONArray("agentActivity").getString(0))
    }

    @Test fun validCandidateCannotReplaceAnAlreadyCorruptHistory() {
        val corrupt = """{"chats":{"lost":"""
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        val candidate = JSONObject().put("chats", JSONObject().put("new-chat", JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "replacement")))))
        assertFalse(ChatHistoryStore.writeRoot(context, candidate))
        assertEquals(corrupt, prefs.getString("chats_v2", null))
    }

    @Test fun writeRejectsStructurallyInvalidCandidate() {
        val root = JSONObject().put("chats", JSONObject().put("chat-1", JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", 7).put("text", "bad")))))
        assertFalse(ChatHistoryStore.writeRoot(context, root))
        assertNull(prefs.getString("chats_v2", null))
    }
}
