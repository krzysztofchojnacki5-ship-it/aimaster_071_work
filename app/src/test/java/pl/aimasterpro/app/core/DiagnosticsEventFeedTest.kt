package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsEventFeedTest {
    private fun queue(
        status: MasterTaskQueueStore.ReadStatus = MasterTaskQueueStore.ReadStatus.AVAILABLE,
        events: List<MasterTaskQueueStore.Event> = emptyList()
    ) = MasterTaskQueueStore.Snapshot(status = status, events = events)

    private fun handoffs(
        status: AgentHandoffStore.ReadStatus = AgentHandoffStore.ReadStatus.AVAILABLE,
        messages: List<AgentHandoffStore.Handoff> = emptyList()
    ) = AgentHandoffStore.Snapshot(status = status, messages = messages)

    private fun queueEvent(
        id: String,
        taskId: String = "task-feed-001",
        timestamp: Long,
        operation: String = "AGENT_TRACE",
        actor: String = "agent:critical-reviewer",
        recipient: String = "agent:unit-test-engineer",
        providerId: String = "provider-local",
        model: String = "model-test",
        detail: String = "Bezpieczny wpis."
    ) = MasterTaskQueueStore.Event(
        id = id, taskId = taskId, actor = actor, operation = operation,
        fromStatus = "", toStatus = "HANDOFF_SENT", detail = detail,
        timestamp = timestamp, recipient = recipient, providerId = providerId,
        model = model, runId = 1
    )

    private fun handoff(
        id: String,
        taskId: String = "task-feed-001",
        state: AgentHandoffStore.State = AgentHandoffStore.State.PENDING,
        updatedAt: Long = 20L
    ) = AgentHandoffStore.Handoff(
        id = id, taskId = taskId, runId = 1,
        senderAgentId = "critical-reviewer", recipientAgentId = "unit-test-engineer",
        payload = "Nie udostępniaj payloadu w diagnostyce.",
        state = state, createdAt = 10L, updatedAt = updatedAt,
        claimedAt = if (state == AgentHandoffStore.State.PENDING) 0L else 15L,
        resolvedAt = if (state == AgentHandoffStore.State.MANUALLY_RESOLVED) updatedAt else 0L,
        resolutionActor = if (state == AgentHandoffStore.State.MANUALLY_RESOLVED) "USER" else "",
        resolutionReason = if (state == AgentHandoffStore.State.REJECTED ||
            state == AgentHandoffStore.State.MANUALLY_RESOLVED) "Powód audytowy." else ""
    )

    @Test fun mergesQueueTransitionsAndHandoffStateWithoutExposingPayload() {
        val result = DiagnosticsEventFeed.build(
            queue(events = listOf(queueEvent("event-feed-001", timestamp = 30L))),
            handoffs(messages = listOf(handoff("handoff-feed-001", state = AgentHandoffStore.State.REJECTED)))
        )
        assertEquals(2, result.entries.size)
        assertEquals(setOf("QUEUE", "HANDOFF"), result.entries.map { it.source }.toSet())
        val handoffEntry = result.entries.single { it.source == "HANDOFF" }
        assertEquals("HANDOFF_REJECTED", handoffEntry.operation)
        assertTrue(handoffEntry.detail.contains("Powód audytowy."))
        assertFalse(handoffEntry.detail.contains("Nie udostępniaj payloadu"))
        assertTrue(result.isComplete)
    }

    @Test fun filtersByTaskAgentProviderAndOperation() {
        val events = listOf(
            queueEvent("event-a", taskId = "task-a", timestamp = 10L),
            queueEvent("event-b", taskId = "task-b", timestamp = 11L,
                actor = "agent:debugger", providerId = "provider-cloud", model = "model-other")
        )
        val messages = listOf(handoff("handoff-a", taskId = "task-a"))
        val taskFiltered = DiagnosticsEventFeed.build(
            queue(events = events), handoffs(messages = messages),
            DiagnosticsEventFeed.Filter(taskId = "task-a")
        )
        assertEquals(2, taskFiltered.entries.size)
        val agentFiltered = DiagnosticsEventFeed.build(
            queue(events = events), handoffs(messages = messages),
            DiagnosticsEventFeed.Filter(agentId = "agent:debugger")
        )
        assertEquals(listOf("event-b"), agentFiltered.entries.map { it.id })
        val providerFiltered = DiagnosticsEventFeed.build(
            queue(events = events), handoffs(messages = messages),
            DiagnosticsEventFeed.Filter(providerId = "cloud")
        )
        assertEquals(listOf("event-b"), providerFiltered.entries.map { it.id })
        val operationFiltered = DiagnosticsEventFeed.build(
            queue(events = events), handoffs(messages = messages),
            DiagnosticsEventFeed.Filter(operation = "HANDOFF_PENDING")
        )
        assertEquals(listOf("handoff-a"), operationFiltered.entries.map { it.id })
    }

    @Test fun newestFirstOrderingUsesStableTieBreakersAndClampsLimit() {
        val result = DiagnosticsEventFeed.build(
            queue(events = listOf(
                queueEvent("z-event", timestamp = 50L),
                queueEvent("a-event", timestamp = 50L),
                queueEvent("old-event", timestamp = 1L)
            )),
            handoffs(messages = listOf(handoff("m-handoff", updatedAt = 50L))),
            limit = 0
        )
        assertEquals(1, result.entries.size)
        assertEquals("a-event", result.entries.single().id)

        val full = DiagnosticsEventFeed.build(
            queue(events = listOf(
                queueEvent("z-event", timestamp = 50L),
                queueEvent("a-event", timestamp = 50L)
            )),
            handoffs(messages = listOf(handoff("m-handoff", updatedAt = 50L))),
            limit = 1000
        )
        assertEquals(listOf("a-event", "m-handoff", "z-event"), full.entries.map { it.id })
    }

    @Test fun unavailableSourcesAreNotMisrepresentedAsEmptyAndAvailableSideRemainsVisible() {
        val result = DiagnosticsEventFeed.build(
            queue(status = MasterTaskQueueStore.ReadStatus.UNAVAILABLE),
            handoffs(messages = listOf(handoff("handoff-visible")))
        )
        assertEquals(MasterTaskQueueStore.ReadStatus.UNAVAILABLE, result.queueStatus)
        assertEquals(AgentHandoffStore.ReadStatus.AVAILABLE, result.handoffStatus)
        assertFalse(result.isComplete)
        assertEquals(listOf("handoff-visible"), result.entries.map { it.id })
    }
}
