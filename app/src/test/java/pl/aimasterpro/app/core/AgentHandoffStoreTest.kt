package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentHandoffStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("aiforteca_agent_handoff_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("aiforteca_master_task_queue_v1")
        AgentManagerStore.resetStorageHealthForTests()
    }

    private fun runningTask(): Int {
        assertTrue(MasterTaskQueueStore.enqueue(context, "handoff-task-01", "Współpraca", "Przekaż ustalenia.", now = 10L))
        assertTrue(MasterTaskQueueStore.assign(context, "handoff-task-01",
            listOf("critical-reviewer", "unit-test-engineer"), now = 11L))
        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start przebiegu.", 12L))
        return MasterTaskQueueStore.snapshot(context).tasks.single().runId
    }

    @Test fun sendIsIdempotentAndPersistsOnlyOnePendingMessage() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-001",
            "critical-reviewer", "unit-test-engineer", "Sprawdź ryzyko regresji.", 20L))
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-001",
            "critical-reviewer", "unit-test-engineer", "Sprawdź ryzyko regresji.", 21L))
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-001",
            "critical-reviewer", "unit-test-engineer", "Inna treść pod tym samym ID.", 22L))
        val snapshot = AgentHandoffStore.snapshot(context)
        assertEquals(AgentHandoffStore.ReadStatus.AVAILABLE, snapshot.status)
        assertEquals(1, snapshot.messages.size)
        assertEquals(AgentHandoffStore.State.PENDING, snapshot.messages.single().state)
    }

    @Test fun sanitizedPayloadRetryRemainsIdempotent() {
        val runId = runningTask()
        val raw = "Raport: api_key=sk_live_1234567890"
        assertEquals("Raport: api_key=[REDACTED]", CommunicationLogSanitizer.sanitize(raw))
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-006",
            "critical-reviewer", "unit-test-engineer", raw, 23L))
        assertTrue("Retry with the same raw payload must compare against its sanitized form",
            AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-006",
                "critical-reviewer", "unit-test-engineer", raw, 24L))
        val snapshot = AgentHandoffStore.snapshot(context)
        assertEquals(1, snapshot.messages.size)
        assertEquals("Raport: api_key=[REDACTED]", snapshot.messages.single().payload)
    }

    @Test fun onlyAssignedSenderAndAddressedRecipientCanClaimAndAcknowledge() {
        val runId = runningTask()
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-002",
            "debugger", "unit-test-engineer", "Niedozwolony nadawca.", 20L))
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-002",
            "critical-reviewer", "unit-test-engineer", "Sprawdź testy.", 21L))
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-002",
            "critical-reviewer", 22L))
        val claimed = AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-002",
            "unit-test-engineer", 23L)
        assertNotNull(claimed)
        assertEquals(AgentHandoffStore.State.CLAIMED, claimed?.state)
        assertFalse(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-msg-002",
            "critical-reviewer", 24L))
        assertTrue(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-msg-002",
            "unit-test-engineer", 25L))
        assertTrue(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-msg-002",
            "unit-test-engineer", 26L))
        assertEquals(AgentHandoffStore.State.ACKNOWLEDGED,
            AgentHandoffStore.snapshot(context).messages.single().state)
    }

    @Test fun claimedMessageIsNotReplayedAfterStoreIsReadAgain() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-003",
            "critical-reviewer", "unit-test-engineer", "Wykonaj kontrolę.", 30L))
        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-003",
            "unit-test-engineer", 31L))
        // A fresh snapshot simulates process recreation; uncertain work is never auto-replayed.
        assertEquals(AgentHandoffStore.State.CLAIMED,
            AgentHandoffStore.snapshot(context).messages.single().state)
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-003",
            "unit-test-engineer", 32L))
    }

    @Test fun staleRunAndUnassignedRecipientsAreRejected() {
        val runId = runningTask()
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId - 1, "handoff-msg-004",
            "critical-reviewer", "unit-test-engineer", "Stary przebieg.", 40L))
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-005",
            "critical-reviewer", "debugger", "Odbiorca spoza zespołu.", 41L))
        assertTrue(AgentHandoffStore.snapshot(context).messages.isEmpty())
    }
}
