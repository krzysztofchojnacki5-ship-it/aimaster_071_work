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
        assertFalse(handoffEntry.detail.contains("Powód audytowy."))
        assertTrue(handoffEntry.detail.contains("Odbiorca odrzucił przekazanie."))
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
            DiagnosticsEventFeed.Filter(taskId = " task-a ")
        )
        assertEquals(2, taskFiltered.entries.size)
        val agentFiltered = DiagnosticsEventFeed.build(
            queue(events = events), handoffs(messages = messages),
            DiagnosticsEventFeed.Filter(agentId = " DEBUGGER ")
        )
        assertEquals(listOf("event-b"), agentFiltered.entries.map { it.id })
        val providerFiltered = DiagnosticsEventFeed.build(
            queue(events = events), handoffs(messages = messages),
            DiagnosticsEventFeed.Filter(providerId = " CLOUD ")
        )
        assertEquals(listOf("event-b"), providerFiltered.entries.map { it.id })
        val operationFiltered = DiagnosticsEventFeed.build(
            queue(events = events), handoffs(messages = messages),
            DiagnosticsEventFeed.Filter(operation = " handoff_pending ")
        )
        assertEquals(listOf("handoff-a"), operationFiltered.entries.map { it.id })
    }

    @Test fun queueAgentEventsExposeStateButNeverFreeFormMessageOrReportBodies() {
        val secretBodies = listOf(
            "prywatny raport agenta",
            "pełna treść wiadomości agentów",
            "szczegóły postępu z odpowiedzią dostawcy",
            "surowy ślad zawierający fragment odpowiedzi"
        )
        val events = listOf(
            queueEvent("result-event", timestamp = 10L, operation = "AGENT_RESULT",
                detail = secretBodies[0]).copy(toStatus = "COMPLETED"),
            queueEvent("message-event", timestamp = 11L, operation = "AGENT_MESSAGE",
                detail = secretBodies[1]).copy(toStatus = "MESSAGE"),
            queueEvent("progress-event", timestamp = 12L, operation = "AGENT_PROGRESS",
                detail = secretBodies[2]).copy(toStatus = "RUNNING"),
            queueEvent("trace-event", timestamp = 13L, operation = "AGENT_TRACE",
                detail = secretBodies[3]).copy(toStatus = "PROVIDER_RESPONSE")
        )

        val result = DiagnosticsEventFeed.build(queue(events = events), handoffs())

        val renderedDetails = result.entries.joinToString(" ") { it.detail }
        secretBodies.forEach { assertFalse(renderedDetails.contains(it)) }
        assertTrue(renderedDetails.contains("COMPLETED"))
        assertTrue(renderedDetails.contains("MESSAGE"))
        assertTrue(renderedDetails.contains("RUNNING"))
        assertTrue(renderedDetails.contains("PROVIDER_RESPONSE"))
    }

    @Test fun queueAndHandoffDetailsNeverExposeFreeFormContent() {
        val privateDetails = listOf(
            "CREATE secret title",
            "ASSIGN private assignment detail",
            "TRANSITION user supplied explanation",
            "AGENT_RESULT private report body",
            "AGENT_MESSAGE private message body",
            "AGENT_PROGRESS provider response fragment",
            "AGENT_TRACE raw trace excerpt",
            "RECOVER_INTERRUPTED private stack detail",
            "UNKNOWN private fallback body"
        )
        val operations = listOf(
            "CREATE", "ASSIGN", "TRANSITION", "AGENT_RESULT", "AGENT_MESSAGE",
            "AGENT_PROGRESS", "AGENT_TRACE", "RECOVER_INTERRUPTED", "UNRECOGNIZED"
        )
        val queueEvents = operations.mapIndexed { index, operation ->
            queueEvent(
                id = "privacy-event-$index",
                timestamp = index.toLong() + 1L,
                operation = operation,
                detail = privateDetails[index]
            ).copy(fromStatus = "RUNNING", toStatus = "BLOCKED")
        }
        val handoffMessages = listOf(
            handoff("private-rejected", state = AgentHandoffStore.State.REJECTED)
                .copy(resolutionReason = "private rejection reason"),
            handoff("private-resolved", state = AgentHandoffStore.State.MANUALLY_RESOLVED)
                .copy(resolutionReason = "private manual resolution reason")
        )

        val result = DiagnosticsEventFeed.build(
            queue(events = queueEvents),
            handoffs(messages = handoffMessages),
            limit = 100
        )
        val rendered = result.entries.joinToString(" ") { it.detail }
        (privateDetails + listOf("private rejection reason", "private manual resolution reason"))
            .forEach { assertFalse("Leaked: $it", rendered.contains(it)) }
        assertTrue(rendered.contains("RUNNING"))
        assertTrue(rendered.contains("BLOCKED"))
        assertTrue(rendered.contains("Odbiorca odrzucił przekazanie."))
        assertTrue(rendered.contains("Ręcznie zamknięto jako wynik nieznany"))
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
