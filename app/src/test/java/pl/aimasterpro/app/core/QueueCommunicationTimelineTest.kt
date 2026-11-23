package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class QueueCommunicationTimelineTest {
    private fun event(
        id: String,
        task: String = "task-1",
        actor: String = "agent:researcher",
        operation: String = "AGENT_MESSAGE",
        toStatus: String = "MESSAGE",
        timestamp: Long,
        recipient: String = "agent:architect",
        providerId: String = "",
        model: String = "",
        runId: Int = 1
    ) = MasterTaskQueueStore.Event(
        id = id,
        taskId = task,
        actor = actor,
        operation = operation,
        fromStatus = "",
        toStatus = toStatus,
        detail = "payload-$id",
        timestamp = timestamp,
        recipient = recipient,
        providerId = providerId,
        model = model,
        runId = runId
    )

    @Test fun labelsCanonicalAgentIdsIncludingDisabledAgents() {
        val message = event("message-1")
        val result = QueueCommunicationTimeline.project(
            listOf(message),
            mapOf("researcher" to "Researcher", "architect" to "Systems Architect")
        ).single()

        assertEquals("Researcher", result.senderLabel)
        assertEquals("Systems Architect", result.recipientLabel)
    }

    @Test fun providerMetadataMustMatchTaskRunSenderAndPrecedeMessage() {
        val events = listOf(
            event("old-run", operation = "AGENT_PROGRESS", toStatus = "REQUESTING",
                timestamp = 10, providerId = "old-provider", model = "old-model", runId = 0),
            event("other-task", task = "task-2", operation = "AGENT_PROGRESS",
                timestamp = 20, providerId = "other-task-provider", model = "other-task-model"),
            event("other-agent", actor = "agent:architect", operation = "AGENT_PROGRESS",
                timestamp = 30, providerId = "other-agent-provider", model = "other-agent-model"),
            event("future", operation = "AGENT_PROGRESS", timestamp = 200,
                providerId = "future-provider", model = "future-model"),
            event("current", operation = "AGENT_PROGRESS", timestamp = 100,
                providerId = "current-provider", model = "current-model"),
            event("message-1", timestamp = 150)
        )

        val result = QueueCommunicationTimeline.project(events, emptyMap()).single()
        assertEquals("current-provider / current-model", result.providerLabel)
    }

    @Test fun messageMetadataTakesPrecedenceOverProgressFallback() {
        val events = listOf(
            event("progress", operation = "AGENT_PROGRESS", toStatus = "REQUESTING",
                timestamp = 10, providerId = "progress-provider", model = "progress-model"),
            event("message", timestamp = 20, providerId = "message-provider", model = "message-model")
        )

        val result = QueueCommunicationTimeline.project(events, emptyMap()).single()
        assertEquals("message-provider / message-model", result.providerLabel)
    }

    @Test fun projectionKeepsNewestMessagesFirstAndHonorsLimit() {
        val events = (1L..4L).map { event("message-$it", timestamp = it) } +
            event("progress", operation = "AGENT_PROGRESS", toStatus = "REQUESTING",
                timestamp = 5, providerId = "provider", model = "model")

        val result = QueueCommunicationTimeline.project(events, emptyMap(), limit = 2)
        assertEquals(listOf("message-4", "message-3"), result.map { it.event.id })
    }

    @Test fun technicalTracesAndUnknownStatusesAreNotShownAsConversation() {
        val events = listOf(
            event("trace", operation = "AGENT_TRACE", toStatus = "REQUEST"),
            event("unknown", operation = "AGENT_MESSAGE", toStatus = "INTERNAL")
        )

        assertTrue(QueueCommunicationTimeline.project(events, emptyMap()).isEmpty())
    }
}
