package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MasterTaskQueueStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("aiforteca_master_task_queue_v1")
        AgentManagerStore.resetStorageHealthForTests()
    }

    @Test fun enqueueAssignAndAgentCompletionArePersistedWithAuditTrail() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-001", "Audyt", "Sprawdź architekturę.", now = 10L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-001", listOf("critical-reviewer"), now = 11L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-001", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Agent rozpoczął analizę.", 12L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-001", "agent:critical-reviewer",
            MasterTaskQueueStore.Status.COMPLETED, "Raport gotowy.", 13L))

        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.ReadStatus.AVAILABLE, state.status)
        assertEquals(MasterTaskQueueStore.Status.COMPLETED, state.tasks.single().status)
        assertEquals(listOf("critical-reviewer"), state.tasks.single().assignedAgentIds)
        assertEquals(listOf("CREATE", "ASSIGN", "TRANSITION", "TRANSITION"), state.events.map { it.operation })
        assertEquals("agent:critical-reviewer", state.events.last().actor)
    }

    @Test fun agentCannotCompleteAnotherAgentsTaskOrPauseTask() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-002", "Test", "Cel.", now = 20L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-002", listOf("critical-reviewer"), now = 21L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-002", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 22L))
        assertFalse(MasterTaskQueueStore.transition(context, "task-002", "agent:unit-test-engineer",
            MasterTaskQueueStore.Status.COMPLETED, "Nieuprawniony agent.", 23L))
        assertFalse(MasterTaskQueueStore.transition(context, "task-002", "agent:critical-reviewer",
            MasterTaskQueueStore.Status.PAUSED, "Agent nie może pauzować koordynacji.", 23L))
        assertEquals(3, MasterTaskQueueStore.snapshot(context).events.size)
        assertEquals(MasterTaskQueueStore.Status.RUNNING, MasterTaskQueueStore.snapshot(context).tasks.single().status)
    }

    @Test fun disabledAgentCannotBeAssigned() {
        assertTrue(AgentManagerStore.setEnabled(context, "critical-reviewer", false))
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-003", "Test", "Cel.", now = 30L))
        assertFalse(MasterTaskQueueStore.assign(context, "task-003", listOf("critical-reviewer"), now = 31L))
        assertEquals(MasterTaskQueueStore.Status.QUEUED, MasterTaskQueueStore.snapshot(context).tasks.single().status)
    }

    @Test fun dependenciesMustBeCompletedBeforeDependentTaskCanBeEnqueuedOrAssigned() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-004", "Pierwsze", "Cel.", now = 40L))
        assertFalse(MasterTaskQueueStore.enqueue(context, "task-005", "Drugie", "Cel.",
            dependencies = listOf("task-004"), now = 41L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-004", listOf("critical-reviewer"), now = 42L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-004", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 43L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-004", "agent:critical-reviewer",
            MasterTaskQueueStore.Status.COMPLETED, "Gotowe.", 44L))
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-005", "Drugie", "Cel.",
            dependencies = listOf("task-004"), now = 45L))
    }

    @Test fun multiAgentTaskCompletesOnlyAfterEveryAssignedAgentReports() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-008", "Zespół", "Wspólny cel.", now = 60L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-008",
            listOf("critical-reviewer", "unit-test-engineer"), now = 61L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-008", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "TeamRunEngine start.", 62L))

        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-008", "critical-reviewer",
            MasterTaskQueueStore.Status.COMPLETED, "Recenzja gotowa.", 63L))
        assertEquals(MasterTaskQueueStore.Status.RUNNING,
            MasterTaskQueueStore.snapshot(context).tasks.single().status)
        assertFalse(MasterTaskQueueStore.recordAgentResult(context, "task-008", "critical-reviewer",
            MasterTaskQueueStore.Status.COMPLETED, "Duplikat wyniku.", 64L))
        assertFalse(MasterTaskQueueStore.recordAgentResult(context, "task-008", "debugger",
            MasterTaskQueueStore.Status.COMPLETED, "Agent spoza przydziału.", 64L))

        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-008", "unit-test-engineer",
            MasterTaskQueueStore.Status.COMPLETED, "Testy gotowe.", 65L))
        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.COMPLETED, state.tasks.single().status)
        assertEquals(2, state.events.count { it.operation == "AGENT_RESULT" })
    }

    @Test fun failedAgentMakesMultiAgentTaskFailedAfterAllReports() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-009", "Zespół", "Wspólny cel.", now = 70L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-009",
            listOf("critical-reviewer", "unit-test-engineer"), now = 71L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-009", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "TeamRunEngine start.", 72L))
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-009", "critical-reviewer",
            MasterTaskQueueStore.Status.FAILED, "Błąd providera.", 73L))
        assertEquals(MasterTaskQueueStore.Status.RUNNING,
            MasterTaskQueueStore.snapshot(context).tasks.single().status)
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-009", "unit-test-engineer",
            MasterTaskQueueStore.Status.COMPLETED, "Drugi agent zakończył.", 74L))
        assertEquals(MasterTaskQueueStore.Status.FAILED,
            MasterTaskQueueStore.snapshot(context).tasks.single().status)
    }

    @Test fun terminalTaskCannotBeReopenedAndCorruptStorageFailsClosed() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-006", "Final", "Cel.", now = 50L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-006", "MASTER",
            MasterTaskQueueStore.Status.CANCELLED, "Anulowano.", 51L))
        assertFalse(MasterTaskQueueStore.transition(context, "task-006", "MASTER",
            MasterTaskQueueStore.Status.QUEUED, "Nie wolno wznowić terminalnego.", 52L))
        context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
            .edit().putString("state", "{broken").commit()
        assertEquals(MasterTaskQueueStore.ReadStatus.CORRUPT, MasterTaskQueueStore.snapshot(context).status)
        assertFalse(MasterTaskQueueStore.enqueue(context, "task-007", "Nowe", "Cel.", now = 53L))
        assertEquals("{broken", context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
            .getString("state", null))
    }
}
