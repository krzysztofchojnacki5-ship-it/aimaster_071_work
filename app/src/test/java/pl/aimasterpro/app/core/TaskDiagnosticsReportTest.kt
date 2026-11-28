package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class TaskDiagnosticsReportTest {
    private fun task() = MasterTaskQueueStore.Task(
        id = "task-diag-001", title = "Test komunikacji", objective = "Sprawdź przekazanie kodu.",
        assignedAgentIds = listOf("lead-coordinator", "planning-lead"),
        dependencies = emptyList(), priority = 2, status = MasterTaskQueueStore.Status.COMPLETED,
        currentStep = "Wyniki wszystkich agentów zapisane.", createdAt = 10L, updatedAt = 50L, runId = 2
    )

    private fun queue(status: MasterTaskQueueStore.ReadStatus = MasterTaskQueueStore.ReadStatus.AVAILABLE) =
        MasterTaskQueueStore.Snapshot(
            status = status,
            tasks = listOf(task()),
            events = listOf(
                MasterTaskQueueStore.Event(
                    id = "event-diag-001", taskId = "task-diag-001", actor = "agent:lead-coordinator",
                    operation = "AGENT_RESULT", fromStatus = "RUNNING", toStatus = "COMPLETED",
                    detail = "Raport zapisany.", timestamp = 40L, providerId = "groq",
                    model = "gpt-oss-20b", runId = 2
                )
            )
        )

    private fun handoffs(status: AgentHandoffStore.ReadStatus = AgentHandoffStore.ReadStatus.AVAILABLE) =
        AgentHandoffStore.Snapshot(
            status = status,
            messages = listOf(
                AgentHandoffStore.Handoff(
                    id = "handoff-diag-001", taskId = "task-diag-001", runId = 2,
                    senderAgentId = "lead-coordinator", recipientAgentId = "planning-lead",
                    payload = "Kod kontrolny: ABCD1234",
                    state = AgentHandoffStore.State.ACKNOWLEDGED,
                    createdAt = 20L, updatedAt = 30L, claimedAt = 25L
                )
            )
        )

    @Test fun buildsOneTaskReportWithTimelineHandoffAndFullAgentReports() {
        val reports = listOf(
            TaskDiagnosticsReport.AgentReport("lead-coordinator", "groq", "model-a", 35L, "Raport pierwszego agenta.", 2),
            TaskDiagnosticsReport.AgentReport("planning-lead", "openai", "model-b", 36L, "Odebrany kod: ABCD1234.", 2)
        )
        val result = TaskDiagnosticsReport.build(task(), queue(), handoffs(), reports, true, 100L)
        assertTrue(result.isComplete)
        assertTrue(result.missing.isEmpty())
        assertTrue(result.text.contains("task-diag-001"))
        assertTrue(result.text.contains("run #2"))
        assertTrue(result.text.contains("HANDOFF_ACKNOWLEDGED").not()) // handoff state is explicit below
        assertTrue(result.text.contains("ACKNOWLEDGED"))
        assertTrue(result.text.contains("Kod kontrolny: ABCD1234"))
        assertTrue(result.text.contains("Odebrany kod: ABCD1234."))
        assertTrue(result.text.contains("groq/model-a"))
    }

    @Test fun incompleteSourcesAndMissingAgentReportsAreExplicit() {
        val result = TaskDiagnosticsReport.build(
            task(), queue(MasterTaskQueueStore.ReadStatus.UNAVAILABLE),
            handoffs(AgentHandoffStore.ReadStatus.CORRUPT),
            emptyList(), false, 100L
        )
        assertFalse(result.isComplete)
        assertTrue(result.text.contains("NIEKOMPLETNY"))
        assertTrue(result.text.contains("DZIENNIK KOLEJKI: UNAVAILABLE"))
        assertTrue(result.text.contains("SKRZYNKA PRZEKAZAŃ: CORRUPT"))
        assertTrue(result.text.contains("ARCHIWUM RAPORTÓW: niedostępne"))
        // The archive itself is unreadable, so per-agent absence cannot be asserted safely.
        assertFalse(result.text.contains("BRAK RAPORTU AGENTA"))
        assertTrue(result.text.contains("nie jest traktowana jako pusta"))
    }

    @Test fun redactsApiCredentialsFromCopiedContext() {
        val secret = "gsk_123456789012345678901234"
        val reports = listOf(
            TaskDiagnosticsReport.AgentReport("lead-coordinator", "groq", "model-a", 35L, "api_key=$secret", 2)
        )
        val result = TaskDiagnosticsReport.build(task(), queue(), handoffs(), reports, true, 100L)
        assertFalse(result.text.contains(secret))
        assertTrue(result.text.contains("[REDACTED]"))
    }

    @Test fun excludesQueueEventsAndHandoffsFromEarlierRuns() {
        val oldEvent = MasterTaskQueueStore.Event(
            id = "event-old-run", taskId = "task-diag-001", actor = "agent:lead-coordinator",
            operation = "OLD_RUN_SECRET_EVENT", fromStatus = "RUNNING", toStatus = "FAILED",
            detail = "old-run-event-detail", timestamp = 15L, runId = 1
        )
        val oldHandoff = AgentHandoffStore.Handoff(
            id = "handoff-old-run", taskId = "task-diag-001", runId = 1,
            senderAgentId = "lead-coordinator", recipientAgentId = "planning-lead",
            payload = "old-run-handoff-secret", state = AgentHandoffStore.State.ACKNOWLEDGED,
            createdAt = 12L, updatedAt = 14L, claimedAt = 13L
        )
        val result = TaskDiagnosticsReport.build(
            task(), queue().copy(events = queue().events + oldEvent),
            handoffs().copy(messages = handoffs().messages + oldHandoff),
            listOf(
                TaskDiagnosticsReport.AgentReport("lead-coordinator", "groq", "model-a", 35L, "Raport A", 2),
                TaskDiagnosticsReport.AgentReport("planning-lead", "openai", "model-b", 36L, "Raport B", 2)
            ), true, 100L
        )
        assertTrue(result.isComplete)
        assertFalse(result.text.contains("OLD_RUN_SECRET_EVENT"))
        assertFalse(result.text.contains("old-run-event-detail"))
        assertFalse(result.text.contains("old-run-handoff-secret"))
    }

    @Test fun reportsMustMatchAssignedAgentAndCurrentRun() {
        val result = TaskDiagnosticsReport.build(
            task(), queue(), handoffs(),
            listOf(
                TaskDiagnosticsReport.AgentReport("lead-coordinator", "groq", "model-a", 35L, "Raport aktualny", 2),
                TaskDiagnosticsReport.AgentReport("planning-lead", "openai", "model-old", 20L, "STARY RAPORT", 1),
                TaskDiagnosticsReport.AgentReport("unassigned-agent", "openai", "model-x", 21L, "NIEPRZYPISANY RAPORT", 2)
            ), true, 100L
        )
        assertFalse(result.isComplete)
        assertTrue(result.missing.any { it.contains("BRAK RAPORTU AGENTA: planning-lead") })
        assertTrue(result.text.contains("Raport aktualny"))
        assertFalse(result.text.contains("STARY RAPORT"))
        assertFalse(result.text.contains("NIEPRZYPISANY RAPORT"))
    }

    @Test fun reportCountIsBoundedAndTruncationMarksDiagnosticsIncomplete() {
        val assigned = (1..33).map { "agent-$it" }
        val expandedTask = task().copy(assignedAgentIds = assigned)
        val reports = assigned.mapIndexed { index, agentId ->
            TaskDiagnosticsReport.AgentReport(agentId, "provider", "model", index.toLong(), "REPORT-$index", 2)
        }
        val result = TaskDiagnosticsReport.build(expandedTask, queue(), handoffs(), reports, true, 100L)
        assertFalse(result.isComplete)
        assertTrue(result.missing.any { it.contains("pokazano ostatnie 32 z 33") })
        assertFalse(result.text.contains("REPORT-0"))
        assertTrue(result.text.contains("REPORT-32"))
    }

    @Test fun overallReportTruncationCannotBeReportedAsComplete() {
        val assigned = (1..6).map { "large-agent-$it" }
        val expandedTask = task().copy(assignedAgentIds = assigned)
        val reports = assigned.mapIndexed { index, agentId ->
            TaskDiagnosticsReport.AgentReport(agentId, "provider", "model", index.toLong(), "x".repeat(32_000), 2)
        }
        val result = TaskDiagnosticsReport.build(expandedTask, queue(), handoffs(), reports, true, 100L)
        assertFalse(result.isComplete)
        assertTrue(result.missing.any { it.contains("całość przekroczyła limit") })
        assertTrue(result.text.contains("Status kompletności: NIEKOMPLETNY"))
        assertTrue(result.text.contains("[OBCIĘTO: raport przekroczył limit"))
    }
}
