package pl.aimasterpro.app.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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


    @Test fun duplicateNonBlankAssistantTaskIdsRejectWholeSnapshotButLegacyPlaceholdersRemainValid() {
        val duplicateIds = JSONObject()
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "assistant").put("text", "first"))
                .put(JSONObject().put("role", "assistant").put("text", "second")))
            .put("assistantTaskIds", JSONArray().put("same-task").put("same-task"))
        assertNull(ChatConversationSnapshot.parse(duplicateIds))

        val legacyPlaceholders = JSONObject()
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "assistant").put("text", "legacy one"))
                .put(JSONObject().put("role", "assistant").put("text", "legacy two")))
            .put("assistantTaskIds", JSONArray().put("").put(""))
        assertEquals(listOf("", ""), ChatConversationSnapshot.parse(legacyPlaceholders)?.assistantTaskIds)
    }

    @Test fun oversizedCollectionsAndFieldsRejectWholeSnapshot() {
        val tooManyMessages = JSONArray()
        repeat(5_001) { tooManyMessages.put(JSONObject().put("role", "user").put("text", "x")) }
        assertNull(ChatConversationSnapshot.parse(JSONObject().put("messages", tooManyMessages)))
        val tooManyActivity = JSONArray()
        repeat(151) { tooManyActivity.put("activity") }
        assertNull(ChatConversationSnapshot.parse(JSONObject().put("agentActivity", tooManyActivity)))
        assertNull(ChatConversationSnapshot.parse(JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "r".repeat(33)).put("text", "x")))))
        assertNull(ChatConversationSnapshot.parse(JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "x".repeat(32_001))))))
        assertNull(ChatConversationSnapshot.parse(JSONObject().put("assistantTaskIds", JSONArray().put("t".repeat(513)))))
    }
}
