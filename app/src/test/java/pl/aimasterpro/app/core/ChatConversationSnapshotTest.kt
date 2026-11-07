package pl.aimasterpro.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatConversationSnapshotTest {
    @Test fun missingChatProducesAnEmptySnapshot() {
        val snapshot = ChatConversationSnapshot.parse(null)!!
        assertTrue(snapshot.messages.isEmpty())
        assertTrue(snapshot.assistantTaskIds.isEmpty())
        assertTrue(snapshot.agentActivity.isEmpty())
    }

    @Test fun validConversationRestoresAllThreeCollectionsTogether() {
        val chat = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "hello")))
            .put("assistantTaskIds", JSONArray().put("task-1"))
            .put("agentActivity", JSONArray().put("agent-started"))

        val snapshot = ChatConversationSnapshot.parse(chat)!!
        assertEquals(listOf("user" to "hello"), snapshot.messages)
        assertEquals(listOf("task-1"), snapshot.assistantTaskIds)
        assertEquals(listOf("agent-started"), snapshot.agentActivity)
    }

    @Test fun malformedActivityRejectsWholeSnapshotWithoutPartialMessages() {
        val chat = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "hello")))
            .put("assistantTaskIds", JSONArray().put("task-1"))
            .put("agentActivity", JSONArray().put(JSONObject()))

        assertNull(ChatConversationSnapshot.parse(chat))
    }

    @Test fun malformedMessageRejectsWholeSnapshot() {
        val chat = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user")))
            .put("agentActivity", JSONArray().put("agent-started"))

        assertNull(ChatConversationSnapshot.parse(chat))
    } 
    @Test fun nonArrayCollectionRejectsWholeSnapshot() {
        val chat = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "hello")))
            .put("assistantTaskIds", JSONObject())
        assertNull(ChatConversationSnapshot.parse(chat))
    }

    @Test fun nonArrayActivityCollectionRejectsWholeSnapshot() {
        val chat = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "hello")))
            .put("agentActivity", JSONObject())
        assertNull(ChatConversationSnapshot.parse(chat))
    }

}
