package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test fun delayedLifecycleCallbackCannotMoveTaskOrAuditTimelineBackwards() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-clock-001", "Zegar", "Test czasu.", now = 100L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-clock-001", listOf("critical-reviewer"), now = 110L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-clock-001", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 120L))

        // Simulate a delayed callback carrying a timestamp from before the device clock correction.
        assertTrue(MasterTaskQueueStore.transition(context, "task-clock-001", "agent:critical-reviewer",
            MasterTaskQueueStore.Status.COMPLETED, "Gotowe.", 90L))

        val snapshot = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.COMPLETED, snapshot.tasks.single().status)
        assertEquals(120L, snapshot.tasks.single().updatedAt)
        assertEquals(listOf(100L, 110L, 120L, 120L), snapshot.events.map { it.timestamp })
    }

    @Test fun activeRunFenceRejectsStaleAndCancelledCallbacks() {
        val taskId = "task-active-fence"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Fence", "Odrzuć spóźnione callbacki.", now = 20L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf("critical-reviewer"), now = 21L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 22L))
        val runId = MasterTaskQueueStore.snapshot(context).tasks.single().runId
        var sideEffects = 0

        assertEquals("active", MasterTaskQueueStore.withActiveRun(context, taskId, runId) {
            sideEffects++
            "active"
        })
        assertNull(MasterTaskQueueStore.withActiveRun(context, taskId, runId + 1) {
            sideEffects++
            "stale"
        })
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "USER",
            MasterTaskQueueStore.Status.CANCELLED, "Anulowano.", 23L))
        assertNull(MasterTaskQueueStore.withActiveRun(context, taskId, runId) {
            sideEffects++
            "cancelled"
        })
        assertEquals(1, sideEffects)
    }

    @Test fun cancellationWaitsForAnAtomicActiveRunSideEffect() {
        val taskId = "task-active-lock"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Blokada", "Sprawdź atomowość anulowania.", now = 30L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf("critical-reviewer"), now = 31L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 32L))

        val sideEffectEntered = CountDownLatch(1)
        val releaseSideEffect = CountDownLatch(1)
        val cancelAttempted = CountDownLatch(1)
        val cancelFinished = CountDownLatch(1)
        val sideEffectCompleted = AtomicBoolean(false)
        val worker = Thread {
            MasterTaskQueueStore.withActiveRun(context, taskId, 1) {
                sideEffectEntered.countDown()
                check(releaseSideEffect.await(3, TimeUnit.SECONDS))
                sideEffectCompleted.set(true)
            }
        }
        worker.start()
        assertTrue("Active-run side effect did not start", sideEffectEntered.await(2, TimeUnit.SECONDS))

        val canceller = Thread {
            cancelAttempted.countDown()
            MasterTaskQueueStore.transition(context, taskId, "USER",
                MasterTaskQueueStore.Status.CANCELLED, "Anulowano.", 33L)
            cancelFinished.countDown()
        }
        canceller.start()
        assertTrue(cancelAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("Cancellation must wait while the active-run critical section is held.",
            cancelFinished.await(150, TimeUnit.MILLISECONDS))
        releaseSideEffect.countDown()
        assertTrue("Cancellation did not finish after the side effect released the lock.",
            cancelFinished.await(2, TimeUnit.SECONDS))
        worker.join(2_000)
        canceller.join(2_000)

        assertTrue(sideEffectCompleted.get())
        assertEquals(MasterTaskQueueStore.Status.CANCELLED,
            MasterTaskQueueStore.snapshot(context).tasks.single().status)
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

    @Test fun enabledCustomAgentCanBeAssignedAndDisabledCustomAgentIsRejected() {
        val custom = pl.aimasterpro.app.agents.AgentSpec(
            id = "custom-queue-auditor",
            name = "Audytor kolejki",
            category = "Jakość i bezpieczeństwo",
            responsibility = "Analizuje zadania przypisane przez MASTER-a.",
            keywords = listOf("kolejka", "audyt"),
            systemPromptOverride = "Nie ujawniaj sekretów."
        )
        assertTrue(AgentManagerStore.addCustomAgent(context, custom))
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-custom-001", "Zadanie własnego agenta", "Sprawdź kolejkę.", now = 35L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-custom-001", listOf(custom.id), now = 36L))
        assertEquals(listOf(custom.id), MasterTaskQueueStore.snapshot(context).tasks.single().assignedAgentIds)
        assertTrue(MasterTaskQueueStore.transition(context, "task-custom-001", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start własnego agenta.", 37L))
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-custom-001", custom.id,
            MasterTaskQueueStore.Status.COMPLETED, "Raport własnego agenta.", 38L))
        assertEquals(MasterTaskQueueStore.Status.COMPLETED,
            MasterTaskQueueStore.snapshot(context).tasks.first { it.id == "task-custom-001" }.status)

        assertTrue(AgentManagerStore.setEnabled(context, custom.id, false))
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-custom-002", "Zadanie wyłączonego agenta", "Nie uruchamiaj.", now = 37L))
        assertFalse(MasterTaskQueueStore.assign(context, "task-custom-002", listOf(custom.id), now = 38L))
        assertEquals(MasterTaskQueueStore.Status.QUEUED, MasterTaskQueueStore.snapshot(context).tasks.last().status)
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
        assertTrue(MasterTaskQueueStore.recordAgentMessage(context, "task-008",
            "critical-reviewer", "MASTER", "REPORT", "Analiza zakończona.", 62L))

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
        assertEquals(1, state.events.count { it.operation == "AGENT_MESSAGE" })
        assertEquals("critical-reviewer", state.events.first { it.operation == "AGENT_MESSAGE" }.actor)
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

    @Test fun assignedAgentProgressUpdatesCurrentStepAndAuditWithoutClosingTask() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-011", "Postęp", "Sprawdź status.", now = 100L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-011", listOf("critical-reviewer"), now = 101L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-011", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 102L))
        assertTrue(MasterTaskQueueStore.recordAgentProgress(context, "task-011", "critical-reviewer",
            "REQUESTING", "Wysyłam zapytanie.", "provider-free", "model-free", 1, 103L))
        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.RUNNING, state.tasks.single().status)
        assertTrue(state.tasks.single().currentStep.contains("REQUESTING"))
        assertEquals("AGENT_PROGRESS", state.events.last().operation)
        assertEquals("provider-free", state.events.last().providerId)
        assertEquals("model-free", state.events.last().model)
        assertFalse(MasterTaskQueueStore.recordAgentProgress(context, "task-011", "unit-test-engineer",
            "RUNNING", "Obcy agent.", now = 104L))
    }

    @Test fun communicationEventsPersistRecipientAndProviderModelMetadata() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-comms", "Komunikacja", "Test metadanych.", now = 70L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-comms", listOf("critical-reviewer"), now = 71L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-comms", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 72L))
        assertTrue(MasterTaskQueueStore.recordAgentMessage(
            context, "task-comms", "Critical Reviewer", "MASTER", "REPORT",
            "Raport testowy bez sekretów.", now = 73L,
            providerId = "provider-local", model = "model-test"
        ))
        val event = MasterTaskQueueStore.snapshot(context).events.last()
        assertEquals("Critical Reviewer", event.actor)
        assertEquals("MASTER", event.recipient)
        assertEquals("provider-local", event.providerId)
        assertEquals("model-test", event.model)
        assertTrue(event.detail.contains("Raport testowy"))
    }

    @Test fun conversationTimelineExcludesLegacyTechnicalLogsConservatively() {
        fun event(operation: String, status: String) = MasterTaskQueueStore.Event(
            id = "$operation-$status", taskId = "task-legacy", actor = "agent",
            operation = operation, fromStatus = "", toStatus = status,
            detail = "Treść testowa", timestamp = 1L
        )
        assertTrue(MasterTaskQueueStore.isConversationMessage(event("AGENT_MESSAGE", "REPORT")))
        assertTrue(MasterTaskQueueStore.isConversationMessage(event("AGENT_MESSAGE", "HANDOFF")))
        assertTrue(MasterTaskQueueStore.isConversationMessage(event("AGENT_MESSAGE", "MESSAGE")))
        assertTrue(MasterTaskQueueStore.isConversationMessage(event("AGENT_MESSAGE", "PROGRESS")))
        assertFalse(MasterTaskQueueStore.isConversationMessage(event("AGENT_MESSAGE", "NETWORK_FAILOVER")))
        assertFalse(MasterTaskQueueStore.isConversationMessage(event("AGENT_MESSAGE", "REQUEST")))
        assertFalse(MasterTaskQueueStore.isConversationMessage(event("AGENT_MESSAGE", "COMPLETE")))
        assertFalse(MasterTaskQueueStore.isConversationMessage(event("AGENT_TRACE", "REPORT")))
        assertFalse(MasterTaskQueueStore.isConversationMessage(event("AGENT_MESSAGE", "UNKNOWN")))
    }

    @Test fun technicalAgentTraceIsSeparateFromMessagesAndBoundToCurrentRun() {
        val taskId = "task-technical-trace"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Ślady techniczne", "Rozdziel komunikaty i logi.", now = 71L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf("critical-reviewer"), now = 72L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 73L))
        val runId = MasterTaskQueueStore.snapshot(context).tasks.single().runId

        assertTrue(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "provider:OpenRouter", "agent:critical-reviewer",
            "NETWORK_FAILOVER", "Przełączenie po limicie dostawcy.",
            now = 74L, providerId = "openrouter", model = "model-test", expectedRunId = runId
        ))
        assertTrue(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "agent:critical-reviewer", "MASTER",
            "REPORT", "Raport zapisany.", now = 75L, expectedRunId = runId
        ))
        assertFalse(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "provider:OpenRouter", "MASTER",
            "NETWORK_ERROR", "Niepowiązany ślad dostawcy.", now = 76L, expectedRunId = runId
        ))
        assertFalse(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "agent:critical-reviewer", "MASTER",
            "LATE", "Ślad ze starego przebiegu.", now = 77L, expectedRunId = runId - 1
        ))

        val events = MasterTaskQueueStore.snapshot(context).events.filter { it.taskId == taskId }
        assertEquals(2, events.count { it.operation == "AGENT_TRACE" })
        assertEquals(0, events.count { it.operation == "AGENT_MESSAGE" })
        val trace = events.first { it.operation == "AGENT_TRACE" }
        assertEquals("provider:OpenRouter", trace.actor)
        assertEquals("agent:critical-reviewer", trace.recipient)
        assertEquals("openrouter", trace.providerId)
        assertEquals("model-test", trace.model)
        assertTrue(trace.detail.contains("NETWORK_FAILOVER"))
    }


    @Test fun handoffLifecycleTracesAreDurableAndBoundToAssignedAgentsAndRun() {
        val taskId = "task-handoff-audit"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Audyt przekazania", "Zapisz cykl przekazania raportu.", now = 91L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId,
            listOf("critical-reviewer", "unit-test-engineer"), now = 92L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 93L))
        val runId = MasterTaskQueueStore.snapshot(context).tasks.single().runId

        assertTrue(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "agent:critical-reviewer", "agent:unit-test-engineer",
            "HANDOFF_SENT", "Raport przekazany.", now = 94L, expectedRunId = runId
        ))
        assertTrue(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "agent:critical-reviewer", "agent:unit-test-engineer",
            "HANDOFF_CLAIMED", "Odbiorca przejął raport.", now = 95L, expectedRunId = runId
        ))
        assertTrue(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "agent:critical-reviewer", "agent:unit-test-engineer",
            "HANDOFF_ACKNOWLEDGED", "Odbiorca potwierdził zapis raportu.", now = 96L, expectedRunId = runId
        ))
        assertFalse(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "agent:critical-reviewer", "agent:debugger",
            "HANDOFF_SENT", "Odbiorca spoza przydziału.", now = 97L, expectedRunId = runId
        ))
        assertFalse(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "agent:critical-reviewer", "agent:unit-test-engineer",
            "HANDOFF_ACKNOWLEDGED", "Stary przebieg.", now = 98L, expectedRunId = runId - 1
        ))

        val events = MasterTaskQueueStore.snapshot(context).events.filter {
            it.taskId == taskId && it.operation == "AGENT_TRACE"
        }
        assertEquals(listOf("HANDOFF_SENT", "HANDOFF_CLAIMED", "HANDOFF_ACKNOWLEDGED"),
            events.map { it.toStatus })
        assertTrue(events.all { it.recipient == "agent:unit-test-engineer" })
        assertTrue(events.all { it.runId == runId })
    }

    @Test fun communicationRejectsUnassignedSendersAndRecipients() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-comms-auth", "Autoryzacja komunikacji", "Test granic zespołu.", now = 74L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-comms-auth",
            listOf("critical-reviewer", "unit-test-engineer"), now = 75L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-comms-auth", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 76L))

        assertFalse(MasterTaskQueueStore.recordAgentMessage(
            context, "task-comms-auth", "debugger", "MASTER", "REPORT",
            "Nadawca spoza przydziału.", now = 77L
        ))
        assertFalse(MasterTaskQueueStore.recordAgentMessage(
            context, "task-comms-auth", "critical-reviewer", "debugger", "MESSAGE",
            "Odbiorca spoza przydziału.", now = 78L
        ))
        assertTrue(MasterTaskQueueStore.recordAgentMessage(
            context, "task-comms-auth", "critical-reviewer", "unit-test-engineer", "HANDOFF",
            "Przekazuję ustalenia do drugiego agenta.", now = 79L
        ))
        assertTrue(MasterTaskQueueStore.recordAgentMessage(
            context, "task-comms-auth", "SYSTEM", "critical-reviewer", "PROGRESS",
            "Systemowy wpis do przypisanego agenta.", now = 80L
        ))
        val messages = MasterTaskQueueStore.snapshot(context).events.filter { it.operation == "AGENT_MESSAGE" }
        assertEquals(2, messages.size)
        assertEquals("critical-reviewer", messages[0].actor)
        assertEquals("unit-test-engineer", messages[0].recipient)
    }

    @Test fun interruptedRunningTasksRecoverAsBlockedWithoutAutomaticReplay() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-010", "Przerwane", "Sprawdź wynik.", now = 80L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-010", listOf("critical-reviewer"), now = 81L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-010", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Żądanie mogło zostać wysłane.", 82L))

        // Simulate a wall-clock correction: recovery time must not predate the RUNNING update.
        assertEquals(1, MasterTaskQueueStore.recoverInterruptedRuns(context, now = 81L))
        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.BLOCKED, state.tasks.single().status)
        assertEquals(82L, state.tasks.single().updatedAt)
        assertTrue(state.tasks.single().currentStep.contains("wynik żądań może być nieznany", ignoreCase = true))
        assertEquals("RECOVER_INTERRUPTED", state.events.last().operation)
        assertEquals(82L, state.events.last().timestamp)
        assertEquals(1, state.events.last().runId)
        assertEquals(0, MasterTaskQueueStore.recoverInterruptedRuns(context, now = 91L))
    }

    @Test fun eventJournalRollsOverAtItsBoundWithoutFreezingQueueWrites() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-012", "Długi przebieg", "Test limitu historii.", now = 200L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-012", listOf("critical-reviewer"), now = 201L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-012", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 202L))
        repeat(810) { index ->
            assertTrue(MasterTaskQueueStore.recordAgentProgress(context, "task-012", "critical-reviewer",
                "RUNNING", "Krok $index", attempts = 1, now = 203L + index))
        }
        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.ReadStatus.AVAILABLE, state.status)
        assertEquals(800, state.events.size)
        assertTrue(state.events.last().detail.contains("Krok 809"))
    }

    @Test fun retryAfterBlockedRunStartsFreshAgentResultEpoch() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-retry-001", "Ponowienie", "Sprawdź retry.", now = 300L))
        val agents = listOf("critical-reviewer", "unit-test-engineer")
        assertTrue(MasterTaskQueueStore.assign(context, "task-retry-001", agents, now = 301L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-retry-001", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Pierwszy przebieg.", 302L))
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-retry-001", agents[0],
            MasterTaskQueueStore.Status.COMPLETED, "Pierwszy raport.", 303L))
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-retry-001", agents[1],
            MasterTaskQueueStore.Status.BLOCKED, "Wymaga ponowienia.", 304L))
        assertEquals(MasterTaskQueueStore.Status.BLOCKED, MasterTaskQueueStore.snapshot(context).tasks.single().status)

        assertTrue(MasterTaskQueueStore.transition(context, "task-retry-001", "MASTER",
            MasterTaskQueueStore.Status.QUEUED, "Zatwierdzono ponowienie.", 305L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-retry-001", agents, now = 306L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-retry-001", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Drugi przebieg.", 307L))
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-retry-001", agents[0],
            MasterTaskQueueStore.Status.COMPLETED, "Nowy raport pierwszego agenta.", 308L))
        assertEquals(MasterTaskQueueStore.Status.RUNNING, MasterTaskQueueStore.snapshot(context).tasks.single().status)
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-retry-001", agents[1],
            MasterTaskQueueStore.Status.COMPLETED, "Nowy raport drugiego agenta.", 309L))

        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.COMPLETED, state.tasks.single().status)
        assertEquals(2, state.events.count {
            it.taskId == "task-retry-001" && it.operation == "AGENT_RESULT" && it.runId == state.tasks.single().runId
        })
        assertEquals(2, state.tasks.single().runId)
    }

    @Test fun callbacksFromPreviousRunCannotPolluteRetryProgressMessagesOrResults() {
        val taskId = "task-stale-callback"
        val agent = "critical-reviewer"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Retry epoch", "Reject stale callbacks.", now = 310L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf(agent), now = 311L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER", MasterTaskQueueStore.Status.RUNNING, "Run 1", 312L))
        val firstRun = MasterTaskQueueStore.snapshot(context).tasks.single().runId
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER", MasterTaskQueueStore.Status.BLOCKED, "Block run 1", 313L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER", MasterTaskQueueStore.Status.QUEUED, "Retry", 314L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf(agent), now = 315L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER", MasterTaskQueueStore.Status.RUNNING, "Run 2", 316L))
        val secondRun = MasterTaskQueueStore.snapshot(context).tasks.single().runId
        assertTrue(secondRun > firstRun)
        assertFalse(MasterTaskQueueStore.recordAgentProgress(context, taskId, agent, "RUNNING", "late progress", expectedRunId = firstRun))
        assertFalse(MasterTaskQueueStore.recordAgentMessage(context, taskId, agent, "MASTER", "DONE", "late message", expectedRunId = firstRun))
        assertFalse(MasterTaskQueueStore.recordAgentResult(context, taskId, agent, MasterTaskQueueStore.Status.COMPLETED, "late result", expectedRunId = firstRun))
        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.RUNNING, state.tasks.single().status)
        assertEquals(secondRun, state.tasks.single().runId)
        assertFalse(state.events.any { it.taskId == taskId && it.runId == secondRun && it.detail.contains("late") })
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, taskId, agent, MasterTaskQueueStore.Status.COMPLETED, "fresh result", expectedRunId = secondRun))
    }

    @Test fun lateAgentResultAfterUserCancellationIsRejected() {
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-cancel-race", "Anulowanie", "Test spóźnionego wyniku.", now = 320L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-cancel-race", listOf("critical-reviewer"), now = 321L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-cancel-race", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 322L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-cancel-race", "USER",
            MasterTaskQueueStore.Status.CANCELLED, "Użytkownik anulował zadanie.", 323L))
        assertFalse(MasterTaskQueueStore.recordAgentResult(context, "task-cancel-race", "critical-reviewer",
            MasterTaskQueueStore.Status.COMPLETED, "Spóźniona odpowiedź HTTP.", 324L))
        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.CANCELLED, state.tasks.single().status)
        assertEquals(0, state.events.count { it.taskId == "task-cancel-race" && it.operation == "AGENT_RESULT" })
    }



    @Test fun activeRunCriticalSectionSerializesHandoffAgainstCancellation() {
        val taskId = "task-cancel-lock"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Wyścig", "Atomowość anulowania.", now = 340L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf("critical-reviewer"), now = 341L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 342L))
        val runId = MasterTaskQueueStore.snapshot(context).tasks.single().runId
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)
        val cancelFinished = CountDownLatch(1)
        val actionCompleted = AtomicBoolean(false)
        val actionThread = Thread {
            val result = MasterTaskQueueStore.withActiveRun(context, taskId, runId) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                actionCompleted.set(true)
                true
            }
            assertEquals(true, result)
        }
        actionThread.start()
        assertTrue("Critical section did not start", entered.await(2, TimeUnit.SECONDS))
        val cancelThread = Thread {
            cancelStarted.countDown()
            assertTrue(MasterTaskQueueStore.transition(context, taskId, "USER",
                MasterTaskQueueStore.Status.CANCELLED, "Anulowano.", 343L))
            cancelFinished.countDown()
        }
        cancelThread.start()
        assertTrue(cancelStarted.await(2, TimeUnit.SECONDS))
        assertFalse("Cancellation must wait for the in-flight durable handoff section",
            cancelFinished.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        actionThread.join(2_000L)
        cancelThread.join(2_000L)
        assertFalse(actionThread.isAlive)
        assertFalse(cancelThread.isAlive)
        assertTrue(actionCompleted.get())
        assertEquals(MasterTaskQueueStore.Status.CANCELLED, MasterTaskQueueStore.snapshot(context).tasks.single().status)
        assertNull(MasterTaskQueueStore.withActiveRun(context, taskId, runId) { true })
    }

    @Test fun allLateAgentCallbacksAfterUserCancellationAreRejected() {
        val taskId = "task-cancel-all-callbacks"
        val agent = "critical-reviewer"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Anulowanie", "Odrzuć wszystkie spóźnione callbacki.", now = 330L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf(agent), now = 331L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 332L))
        val runId = MasterTaskQueueStore.snapshot(context).tasks.single().runId
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "USER",
            MasterTaskQueueStore.Status.CANCELLED, "Użytkownik anulował zadanie.", 333L))

        assertFalse(MasterTaskQueueStore.recordAgentProgress(context, taskId, agent,
            "RUNNING", "Spóźniony postęp.", expectedRunId = runId))
        assertFalse(MasterTaskQueueStore.recordAgentMessage(context, taskId, agent, "MASTER",
            "REPORT", "Spóźniona wiadomość.", expectedRunId = runId))
        assertFalse(MasterTaskQueueStore.recordAgentTrace(context, taskId, "agent:$agent", "MASTER",
            "LATE_TRACE", "Spóźniony ślad.", expectedRunId = runId))
        assertFalse(MasterTaskQueueStore.recordAgentResult(context, taskId, agent,
            MasterTaskQueueStore.Status.COMPLETED, "Spóźniony wynik.", expectedRunId = runId))

        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.CANCELLED, state.tasks.single().status)
        assertEquals(1, state.events.count { it.taskId == taskId && it.operation == "TRANSITION" && it.toStatus == "CANCELLED" })
        assertFalse(state.events.any { it.taskId == taskId && it.operation in setOf(
            "AGENT_PROGRESS", "AGENT_MESSAGE", "AGENT_TRACE", "AGENT_RESULT") })
    }

    @Test fun currentRunAgentResultsSurviveRollingJournalRollover() {
        val agents = listOf("critical-reviewer", "unit-test-engineer")
        assertTrue(MasterTaskQueueStore.enqueue(context, "task-result-retention", "Trwałe wyniki", "Test rotacji dziennika.", now = 340L))
        assertTrue(MasterTaskQueueStore.assign(context, "task-result-retention", agents, now = 341L))
        assertTrue(MasterTaskQueueStore.transition(context, "task-result-retention", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 342L))
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-result-retention", agents[0],
            MasterTaskQueueStore.Status.COMPLETED, "Pierwszy raport.", 343L))
        repeat(810) { index ->
            assertTrue(MasterTaskQueueStore.recordAgentProgress(context, "task-result-retention", agents[1],
                "RUNNING", "Krok $index", attempts = 1, now = 344L + index))
        }
        assertTrue(MasterTaskQueueStore.recordAgentResult(context, "task-result-retention", agents[1],
            MasterTaskQueueStore.Status.COMPLETED, "Drugi raport.", 1_200L))
        val state = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.COMPLETED, state.tasks.single().status)
        assertEquals(2, state.events.count {
            it.taskId == "task-result-retention" && it.operation == "AGENT_RESULT" && it.runId == state.tasks.single().runId
        })
        assertTrue(state.events.size <= 810)
    }


    @Test fun malformedQueueEventsFailClosedAndAreNeverOverwritten() {
        val prefs = context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
        val validEvent = org.json.JSONObject()
            .put("id", "event-001")
            .put("taskId", "task-event-001")
            .put("actor", "MASTER")
            .put("operation", "CREATE")
            .put("from", "")
            .put("to", "QUEUED")
            .put("detail", "Utworzono zadanie.")
            .put("timestamp", 100L)
            .put("recipient", "")
            .put("providerId", "")
            .put("model", "")
            .put("runId", 0)
        val malformedEvents = listOf(
            org.json.JSONObject(validEvent.toString()).put("timestamp", -1L),
            org.json.JSONObject(validEvent.toString()).put("runId", -1),
            org.json.JSONObject(validEvent.toString()).put("runId", "1"),
            org.json.JSONObject(validEvent.toString()).put("actor", ""),
            org.json.JSONObject(validEvent.toString()).put("detail", "x".repeat(501)),
            org.json.JSONObject(validEvent.toString()).put("providerId", 7)
        )

        malformedEvents.forEachIndexed { index, event ->
            val raw = org.json.JSONObject()
                .put("version", 1)
                .put("tasks", org.json.JSONArray())
                .put("events", org.json.JSONArray().put(event))
                .toString()
            assertTrue("Fixture must be written: $index", prefs.edit().putString("state", raw).commit())

            assertEquals("Malformed event $index must be rejected",
                MasterTaskQueueStore.ReadStatus.CORRUPT, MasterTaskQueueStore.snapshot(context).status)
            assertFalse("Malformed event $index must block mutation",
                MasterTaskQueueStore.enqueue(context, "task-after-event-$index", "Nowe", "Cel.", now = 200L))
            assertEquals("Corrupt event data must remain intact", raw, prefs.getString("state", null))
        }
    }


    @Test fun preferenceAccessFailureIsUnavailableNotCorruptQueueData() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled queue preference read failure")
            }
        }
        val snapshot = MasterTaskQueueStore.snapshot(failingContext)
        assertEquals(MasterTaskQueueStore.ReadStatus.UNAVAILABLE, snapshot.status)
        assertTrue(snapshot.errorCode.startsWith("STORAGE_READ_FAILED:"))
    }

    @Test fun fractionalTaskLifecycleNumbersAreRejectedWithoutRewritingQueue() {
        val prefs = context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
        val fields = listOf("priority", "createdAt", "updatedAt", "runId")
        fields.forEachIndexed { index, field ->
            val task = org.json.JSONObject()
                .put("id", "task-exact-$index")
                .put("title", "Exact values")
                .put("objective", "Reject fractional persisted numbers.")
                .put("agents", org.json.JSONArray())
                .put("dependencies", org.json.JSONArray())
                .put("priority", 2)
                .put("status", "QUEUED")
                .put("step", "Waiting")
                .put("createdAt", 10L)
                .put("updatedAt", 10L)
                .put("runId", 0)
            task.put(field, 1.5)
            val raw = org.json.JSONObject()
                .put("version", 1)
                .put("tasks", org.json.JSONArray().put(task))
                .put("events", org.json.JSONArray())
                .toString()
            assertTrue(prefs.edit().putString("state", raw).commit())
            assertEquals("Fractional $field must be rejected",
                MasterTaskQueueStore.ReadStatus.CORRUPT, MasterTaskQueueStore.snapshot(context).status)
            assertEquals("Rejected $field must remain untouched", raw, prefs.getString("state", null))
        }
    }

    @Test fun queueRejectsAuditEventsForUnknownTasksWithoutDroppingThem() {
        val prefs = context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
        val orphan = org.json.JSONObject()
            .put("id", "event-orphan-001")
            .put("taskId", "task-orphan-001")
            .put("actor", "MASTER")
            .put("operation", "CREATE")
            .put("from", "")
            .put("to", "QUEUED")
            .put("detail", "Orphan event")
            .put("timestamp", 100L)
            .put("recipient", "")
            .put("providerId", "")
            .put("model", "")
            .put("runId", 0)
        val raw = org.json.JSONObject()
            .put("version", 1)
            .put("tasks", org.json.JSONArray())
            .put("events", org.json.JSONArray().put(orphan))
            .toString()
        assertTrue(prefs.edit().putString("state", raw).commit())
        assertEquals(MasterTaskQueueStore.ReadStatus.CORRUPT, MasterTaskQueueStore.snapshot(context).status)
        assertFalse(MasterTaskQueueStore.enqueue(context, "task-after-orphan", "New", "Must fail closed.", now = 200L))
        assertEquals(raw, prefs.getString("state", null))
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
    @Test fun persistedDependenciesMustReferenceEarlierCompletedTasks() {
        val prefs = context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
        fun task(id: String, status: String, dependencies: org.json.JSONArray = org.json.JSONArray()) =
            org.json.JSONObject()
                .put("id", id).put("title", "Task").put("objective", "Objective")
                .put("agents", org.json.JSONArray()).put("dependencies", dependencies)
                .put("priority", 2).put("status", status).put("step", "Step")
                .put("createdAt", 10L).put("updatedAt", 10L).put("runId", 0)
        val invalidLedgers = listOf(
            org.json.JSONArray().put(task("task-child", "QUEUED",
                org.json.JSONArray().put("task-missing"))),
            org.json.JSONArray().put(task("task-parent", "QUEUED"))
                .put(task("task-child", "QUEUED", org.json.JSONArray().put("task-parent"))),
            org.json.JSONArray().put(task("task-child", "QUEUED",
                org.json.JSONArray().put("task-child")))
        )
        invalidLedgers.forEachIndexed { index, tasks ->
            val raw = org.json.JSONObject().put("version", 1).put("tasks", tasks)
                .put("events", org.json.JSONArray()).toString()
            assertTrue(prefs.edit().putString("state", raw).commit())
            assertEquals("Invalid dependency ledger $index must be rejected",
                MasterTaskQueueStore.ReadStatus.CORRUPT, MasterTaskQueueStore.snapshot(context).status)
            assertEquals("Invalid dependency data must remain untouched", raw, prefs.getString("state", null))
        }
        val valid = org.json.JSONObject().put("version", 1).put("tasks",
            org.json.JSONArray()
                .put(task("task-parent", "COMPLETED"))
                .put(task("task-child", "QUEUED", org.json.JSONArray().put("task-parent"))))
            .put("events", org.json.JSONArray()).toString()
        assertTrue(prefs.edit().putString("state", valid).commit())
        assertEquals(MasterTaskQueueStore.ReadStatus.AVAILABLE, MasterTaskQueueStore.snapshot(context).status)
    }

    @Test fun persistedQueueEventsCannotClaimAFutureRunEpoch() {
        val prefs = context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
        val task = org.json.JSONObject()
            .put("id", "task-run-bound").put("title", "Run bound").put("objective", "Validate event epoch")
            .put("agents", org.json.JSONArray()).put("dependencies", org.json.JSONArray())
            .put("priority", 2).put("status", "QUEUED").put("step", "Queued")
            .put("createdAt", 10L).put("updatedAt", 10L).put("runId", 0)
        val event = org.json.JSONObject()
            .put("id", "event-future-run").put("taskId", "task-run-bound")
            .put("actor", "MASTER").put("operation", "TRANSITION")
            .put("from", "ASSIGNED").put("to", "RUNNING").put("detail", "Impossible future event")
            .put("timestamp", 11L).put("recipient", "").put("providerId", "").put("model", "")
            .put("runId", 1)
        val raw = org.json.JSONObject().put("version", 1)
            .put("tasks", org.json.JSONArray().put(task))
            .put("events", org.json.JSONArray().put(event)).toString()
        assertTrue(prefs.edit().putString("state", raw).commit())
        assertEquals(MasterTaskQueueStore.ReadStatus.CORRUPT, MasterTaskQueueStore.snapshot(context).status)
        assertFalse(MasterTaskQueueStore.enqueue(context, "task-after-future", "New", "Must fail closed.", now = 20L))
        assertEquals(raw, prefs.getString("state", null))
    }
    @Test fun agentCannotCloseMultiAgentTaskWithDirectLifecycleTransition() {
        val taskId = "task-multi-direct-close"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Zespół", "Wymagaj wyników wszystkich agentów.", now = 10L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId,
            listOf("critical-reviewer", "unit-test-engineer"), now = 11L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 12L))

        assertFalse(MasterTaskQueueStore.transition(context, taskId, "agent:critical-reviewer",
            MasterTaskQueueStore.Status.COMPLETED, "Jeden agent nie może zamknąć całego zespołu.", 13L))
        val snapshot = MasterTaskQueueStore.snapshot(context)
        assertEquals(MasterTaskQueueStore.Status.RUNNING, snapshot.tasks.single().status)
        assertEquals(3, snapshot.events.size)
    }

    @Test fun lifecycleTransitionRedactsSecretsBeforePersistingCurrentStep() {
        val taskId = "task-transition-redaction"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Redakcja", "Oczyść opis przejścia.", now = 20L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf("critical-reviewer"), now = 21L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 22L))
        val secret = "sk_live_transition_secret_123456789"

        assertTrue(MasterTaskQueueStore.transition(context, taskId, "USER",
            MasterTaskQueueStore.Status.CANCELLED, "api_key=$secret", 23L))
        val snapshot = MasterTaskQueueStore.snapshot(context)
        assertFalse(snapshot.tasks.single().currentStep.contains(secret))
        assertTrue(snapshot.tasks.single().currentStep.contains("[REDACTED]"))
        assertFalse(snapshot.events.last().detail.contains(secret))
    }

    @Test fun progressRejectsUnknownStateInsteadOfPersistingUntrustedMetadata() {
        val taskId = "task-progress-state-guard"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Postęp", "Waliduj stan.", now = 30L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf("critical-reviewer"), now = 31L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 32L))
        val before = MasterTaskQueueStore.snapshot(context).events.size

        assertFalse(MasterTaskQueueStore.recordAgentProgress(
            context, taskId, "critical-reviewer", "api_key=sk_live_progress_secret_123456",
            "Nieprawidłowy stan.", now = 33L
        ))
        assertEquals(before, MasterTaskQueueStore.snapshot(context).events.size)
        assertTrue(MasterTaskQueueStore.recordAgentProgress(
            context, taskId, "critical-reviewer", "RUNNING", "Bezpieczny stan.", now = 34L
        ))
    }

    @Test fun communicationRejectsUnknownStatusCodes() {
        val taskId = "task-message-status-guard"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Komunikat", "Waliduj status.", now = 40L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf("critical-reviewer"), now = 41L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 42L))
        val before = MasterTaskQueueStore.snapshot(context).events.size

        assertFalse(MasterTaskQueueStore.recordAgentMessage(
            context, taskId, "critical-reviewer", "MASTER", "api_key=sk_live_message_secret_123456",
            "Nie zapisuj statusu dowolnego tekstu.", now = 43L
        ))
        assertEquals(before, MasterTaskQueueStore.snapshot(context).events.size)
    }

    @Test fun technicalTraceRejectsUnsafeTypeAndCredentialBearingSourceIdentity() {
        val taskId = "task-trace-metadata-guard"
        assertTrue(MasterTaskQueueStore.enqueue(context, taskId, "Ślad", "Waliduj metadane śladu.", now = 50L))
        assertTrue(MasterTaskQueueStore.assign(context, taskId, listOf("critical-reviewer"), now = 51L))
        assertTrue(MasterTaskQueueStore.transition(context, taskId, "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start.", 52L))
        val before = MasterTaskQueueStore.snapshot(context).events.size

        assertFalse(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "agent:critical-reviewer", "MASTER",
            "api_key=sk_live_trace_secret_123456", "Nieprawidłowy typ śladu.", now = 53L
        ))
        assertFalse(MasterTaskQueueStore.recordAgentTrace(
            context, taskId, "provider:openrouter api_key=sk_live_trace_secret_123456",
            "agent:critical-reviewer", "NETWORK_FAILOVER", "Niebezpieczna tożsamość źródła.", now = 54L
        ))
        assertEquals(before, MasterTaskQueueStore.snapshot(context).events.size)
    }

}
