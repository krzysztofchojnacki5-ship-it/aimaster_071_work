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
            TaskDiagnosticsReport.AgentReport("lead-coordinator", "groq", "model-a", 35L, "Raport pierwszego agenta."),
            TaskDiagnosticsReport.AgentReport("planning-lead", "openai", "model-b", 36L, "Odebrany kod: ABCD1234.")
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
            TaskDiagnosticsReport.AgentReport("lead-coordinator", "groq", "model-a", 35L, "api_key=$secret")
        )
        val result = TaskDiagnosticsReport.build(task(), queue(), handoffs(), reports, true, 100L)
        assertFalse(result.text.contains(secret))
        assertTrue(result.text.contains("[REDACTED]"))
    }
}
