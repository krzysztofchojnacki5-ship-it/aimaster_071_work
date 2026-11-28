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

    private fun handoff(
        id: String = "handoff-1",
        timestamp: Long = 40,
        state: AgentHandoffStore.State = AgentHandoffStore.State.PENDING,
        sender: String = "researcher",
        recipient: String = "architect",
        task: String = "task-1",
        runId: Int = 1,
        payload: String = "raport poprzedniego agenta"
    ) = AgentHandoffStore.Handoff(
        id = id,
        taskId = task,
        runId = runId,
        senderAgentId = sender,
        recipientAgentId = recipient,
        payload = payload,
        state = state,
        createdAt = timestamp,
        updatedAt = timestamp,
        claimedAt = if (state == AgentHandoffStore.State.PENDING) 0 else timestamp,
        resolvedAt = 0
    )

    @Test fun labelsCanonicalAgentIdsIncludingDisabledAgents() {
        val message = event("message-1", timestamp = 50)
        val result = QueueCommunicationTimeline.project(
            listOf(message), emptyList(),
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

        val result = QueueCommunicationTimeline.project(events, emptyList(), emptyMap()).single()
        assertEquals("current-provider / current-model", result.providerLabel)
    }

    @Test fun messageMetadataTakesPrecedenceOverProgressFallback() {
        val events = listOf(
            event("progress", operation = "AGENT_PROGRESS", toStatus = "REQUESTING",
                timestamp = 10, providerId = "progress-provider", model = "progress-model"),
            event("message", timestamp = 20, providerId = "message-provider", model = "message-model")
        )

        val result = QueueCommunicationTimeline.project(events, emptyList(), emptyMap()).single()
        assertEquals("message-provider / message-model", result.providerLabel)
    }

    @Test fun persistedHandoffPayloadAndStateAppearInCommunicationTimeline() {
        val transfer = handoff(
            timestamp = 40,
            state = AgentHandoffStore.State.CLAIMED,
            payload = "Pełny raport przekazany odbiorcy"
        )
        val result = QueueCommunicationTimeline.project(
            emptyList(), listOf(transfer),
            mapOf("researcher" to "Researcher", "architect" to "Systems Architect")
        ).single()

        assertEquals("Researcher", result.senderLabel)
        assertEquals("Systems Architect", result.recipientLabel)
        assertEquals("HANDOFF_CLAIMED", result.status)
        assertEquals("Pełny raport przekazany odbiorcy", result.detail)
        assertEquals(1, result.runId)
    }

    @Test fun projectionMergesSourcesNewestFirstAndHonorsLimit() {
        val events = (1L..4L).map { event("message-$it", timestamp = it * 10) }
        val handoffs = listOf(handoff(id = "handoff-1", timestamp = 35))
        val result = QueueCommunicationTimeline.project(events, handoffs, emptyMap(), limit = 3)

        assertEquals(listOf(40L, 35L, 30L), result.map { it.timestamp })
        assertEquals("HANDOFF_PENDING", result[1].status)
    }

    @Test fun technicalTracesAndUnknownStatusesAreNotShownAsConversation() {
        val events = listOf(
            event("trace", operation = "AGENT_TRACE", toStatus = "REQUEST", timestamp = 10),
            event("unknown", operation = "AGENT_MESSAGE", toStatus = "INTERNAL", timestamp = 20)
        )

        assertTrue(QueueCommunicationTimeline.project(events, emptyList(), emptyMap()).isEmpty())
    }
}
