package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class TaskRunObservabilityTest {
    private fun task(runId: Int = 2) = MasterTaskQueueStore.Task(
        id = "parent-task", title = "Audyt", objective = "Sprawdź", assignedAgentIds = listOf("reviewer", "builder"),
        dependencies = emptyList(), priority = 1, status = MasterTaskQueueStore.Status.RUNNING,
        currentStep = "Praca", createdAt = 10L, updatedAt = 20L, runId = runId
    )

    @Test fun mapsAgentCheckpointByExecutionIdAndCurrentRunOnly() {
        val t = task()
        val executionId = TaskResultArchive.executionTaskId(t.id, "reviewer", t.runId)
        val rows = listOf(
            TaskCheckpointStore.Event(executionId, "reviewer", "REQUESTING", "Aktualne żądanie", 100L,
                "provider", "model", 1, runId = 2),
            TaskCheckpointStore.Event(executionId, "reviewer", "FAILED", "Stary przebieg", 200L,
                "provider", "model", 1, runId = 1),
            TaskCheckpointStore.Event("unrelated", "builder", "COMPLETED", "Obce", 300L,
                "provider", "model", 1, runId = 2)
        )
        val result = TaskRunObservability.forTask(t, emptyList(), rows, emptySet(), now = 120L)
        assertEquals("REQUESTING", result[0].status)
        assertEquals("Aktualne żądanie", result[0].detail)
        assertEquals("WAITING", result[1].status)
    }

    @Test fun queueProgressIsFencedByParentRunAndReportFlagIsIndependent() {
        val t = task()
        val events = listOf(
            MasterTaskQueueStore.Event("e1", t.id, "agent:reviewer", "AGENT_PROGRESS", "RUNNING",
                "RUNNING", "Nowy postęp", 150L, runId = 2),
            MasterTaskQueueStore.Event("e2", t.id, "agent:reviewer", "AGENT_RESULT", "RUNNING",
                "FAILED", "Stary wynik", 250L, runId = 1)
        )
        val result = TaskRunObservability.forTask(t, events, emptyList(), setOf("reviewer"), now = 160L)
        assertEquals("RUNNING", result[0].status)
        assertEquals("Nowy postęp", result[0].detail)
        assertTrue(result[0].reportAvailable)
        assertEquals("WAITING", result[1].status)
    }

    @Test fun activeProgressBecomesStaleButTerminalResultNeverDoes() {
        val t = task()
        val events = listOf(
            MasterTaskQueueStore.Event("e1", t.id, "agent:reviewer", "AGENT_PROGRESS", "RUNNING",
                "RUNNING", "Pracuje", 100L, runId = 2),
            MasterTaskQueueStore.Event("e2", t.id, "agent:builder", "AGENT_RESULT", "RUNNING",
                "COMPLETED", "Gotowe", 100L, runId = 2)
        )
        val result = TaskRunObservability.forTask(t, events, emptyList(), emptySet(), now = 400_000L, staleAfterMs = 1_000L)
        assertTrue(result[0].stale)
        assertFalse(result[1].stale)
    }
}
