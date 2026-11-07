package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TaskCheckpointStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("task_result_archive_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun staleRequestingTaskReconcilesUnknownUsageBeforeMarkingInterrupted() {
        val event = TaskCheckpointStore.Event(
            taskId = "task-request-in-flight", agentId = "RESEARCHER", status = "REQUESTING",
            detail = "request started", timestamp = 100L, providerId = "openai", model = "gpt-4o-mini",
            attempts = 1, processId = "previous-process", estimatedInputTokens = 450L,
            estimatedOutputTokens = 1_200L, idempotencyKey = "team:task-request-in-flight:openai",
            baseUrl = "https://api.openai.com"
        )
        assertTrue(TaskCheckpointStore.record(context, event))
        var recoveredEvent: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            recoveredEvent = it
            true
        })
        assertEquals(450L, recoveredEvent?.estimatedInputTokens)
        assertEquals(1_200L, recoveredEvent?.estimatedOutputTokens)
        assertEquals("team:task-request-in-flight:openai", recoveredEvent?.idempotencyKey)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
        assertTrue(TaskCheckpointStore.latest(context).single().detail.contains("UNAVAILABLE"))
    }

    @Test fun singleChatRequestCheckpointRetainsProviderAndIdempotencyForRestartRecovery() {
        val event = TaskCheckpointStore.Event(
            taskId = "chat-chat-123-request-456", agentId = "MASTER", status = "REQUESTING",
            detail = "single chat POST", timestamp = 100L, providerId = "openrouter",
            model = "model-x", attempts = 2, processId = "previous-process",
            estimatedInputTokens = 320L, estimatedOutputTokens = 4_096L,
            idempotencyKey = "chat:chat-chat-123-request-456:openrouter",
            baseUrl = "https://openrouter.ai"
        )
        assertTrue(TaskCheckpointStore.record(context, event))
        var recovered: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            recovered = it
            true
        })
        assertEquals("openrouter", recovered?.providerId)
        assertEquals("model-x", recovered?.model)
        assertEquals("chat:chat-chat-123-request-456:openrouter", recovered?.idempotencyKey)
        assertEquals("https://openrouter.ai", recovered?.baseUrl)
        assertEquals(4_096L, recovered?.estimatedOutputTokens)
    }

    @Test fun failedUnknownUsageRecoveryRemainsPendingAndCanBeRetried() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-pending-usage", agentId = "RESEARCHER", status = "REQUESTING",
            detail = "", timestamp = 100L, providerId = "openai", model = "gpt-4o-mini",
            attempts = 1, processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:task-pending-usage:openai"
        )))
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) { false })
        assertEquals("INTERRUPTED_USAGE_PENDING", TaskCheckpointStore.latest(context).single().status)
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 300L) { true })
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun resultPersistencePendingTaskIsRecoveredWithoutRebilling() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-result-pending", agentId = "RESEARCHER", status = "RESULT_PERSIST_PENDING",
            detail = "report cache write failed", timestamp = 100L, providerId = "openai",
            model = "gpt-4o-mini", attempts = 1, processId = "previous-process",
            estimatedInputTokens = 200L, estimatedOutputTokens = 500L,
            idempotencyKey = "team:task-result-pending:openai"
        )))
        var usageRecoveryCalled = false
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            usageRecoveryCalled = true
            false
        })
        assertFalse(usageRecoveryCalled)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
        assertTrue(TaskCheckpointStore.latest(context).single().detail.contains("trwały zapis wyniku"))
    }

    @Test fun timedOutTaskCanBeReconciledOnlyAfterItsReportIsDurablyArchived() {
        val taskId = "task-timeout-report-race"
        val initial = TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "TIMED_OUT",
            detail = "coordinator deadline elapsed", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:$taskId:openai"
        )
        assertTrue(TaskCheckpointStore.record(context, initial))

        // A late callback without durable evidence must not rewrite the terminal state.
        assertTrue(TaskCheckpointStore.record(context, initial.copy(
            status = "COMPLETED", detail = "late callback without archive"
        )))
        assertEquals("TIMED_OUT", TaskCheckpointStore.latest(context).single().status)

        assertTrue(TaskResultArchive.put(
            context, taskId, "RESEARCHER", "Late but durable report.",
            "openai", "gpt-4o-mini", 150L, "chat-timeout-race"
        ))
        assertTrue(TaskCheckpointStore.record(context, initial.copy(
            status = "COMPLETED", detail = "durable report archived", timestamp = 200L
        )))
        val reconciled = TaskCheckpointStore.latest(context).single()
        assertEquals("COMPLETED", reconciled.status)
        assertEquals("openai", reconciled.providerId)
        assertEquals("team:$taskId:openai", reconciled.idempotencyKey)
    }

    @Test fun timedOutTaskCanRecordUsagePendingWithoutArchivedReportWhenMetadataIsComplete() {
        val taskId = "task-timeout-usage-no-report"
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "TIMED_OUT",
            detail = "coordinator deadline elapsed", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:$taskId:openai"
        )))
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "USAGE_PENDING",
            detail = "usage persistence failed", timestamp = 200L
        )))
        val pending = TaskCheckpointStore.latest(context).single()
        assertEquals("USAGE_PENDING", pending.status)
        assertEquals("team:$taskId:openai", pending.idempotencyKey)
        assertEquals(200L, pending.estimatedInputTokens)
        assertEquals(500L, pending.estimatedOutputTokens)
    }

    @Test fun timedOutTaskMayRemainUsagePendingWhenArchivedReportExists() {
        val taskId = "task-timeout-usage-race"
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "TIMED_OUT",
            detail = "coordinator deadline elapsed", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:$taskId:openai"
        )))
        assertTrue(TaskResultArchive.put(
            context, taskId, "RESEARCHER", "Report with usage pending.",
            "openai", "gpt-4o-mini", 150L, "chat-timeout-usage"
        ))

        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "USAGE_PENDING",
            detail = "usage ledger write failed", timestamp = 200L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1
        )))
        val pending = TaskCheckpointStore.latest(context).single()
        assertEquals("USAGE_PENDING", pending.status)
        assertEquals("team:$taskId:openai", pending.idempotencyKey)
        assertEquals(200L, pending.estimatedInputTokens)
        assertEquals(500L, pending.estimatedOutputTokens)
    }

    @Test fun resultPersistPendingTaskIsCompletedWhenDurableArchiveAlreadyContainsReport() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-result-archived", agentId = "RESEARCHER", status = "RESULT_PERSIST_PENDING",
            detail = "report archive write completed; checkpoint update pending", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1, processId = "previous-process"
        )))
        assertTrue(TaskResultArchive.put(
            context, "task-result-archived", "RESEARCHER", "Pełny raport odzyskiwalny po restarcie.",
            "openai", "gpt-4o-mini", 150L
        ))
        var usageRecoveryCalled = false
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            usageRecoveryCalled = true
            false
        })
        assertFalse(usageRecoveryCalled)
        val recovered = TaskCheckpointStore.latest(context).single()
        assertEquals("COMPLETED", recovered.status)
        assertTrue(recovered.detail.contains("trwałego archiwum"))
        assertEquals("Pełny raport odzyskiwalny po restarcie.", TaskResultArchive.get(context, "task-result-archived")?.report)
    }

    @Test fun archivedReportIsRecoveredEvenWhenUsageAccountingWasPending() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-usage-and-report", agentId = "RESEARCHER", status = "USAGE_PENDING",
            detail = "usage write failed after response", timestamp = 100L, providerId = "openai",
            model = "gpt-4o-mini", attempts = 1, processId = "previous-process",
            estimatedInputTokens = 200L, estimatedOutputTokens = 500L,
            idempotencyKey = "team:task-usage-and-report:openai"
        )))
        assertTrue(TaskResultArchive.put(
            context, "task-usage-and-report", "RESEARCHER", "Odzyskiwalny raport.",
            "openai", "gpt-4o-mini", 150L
        ))

        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) { false })
        assertEquals("INTERRUPTED_USAGE_PENDING", TaskCheckpointStore.latest(context).single().status)
        assertEquals("Odzyskiwalny raport.", TaskResultArchive.get(context, "task-usage-and-report")?.report)

        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 300L) { true })
        val recovered = TaskCheckpointStore.latest(context).single()
        assertEquals("COMPLETED", recovered.status)
        assertTrue(recovered.detail.contains("archiwum"))
        assertEquals("Odzyskiwalny raport.", TaskResultArchive.get(context, "task-usage-and-report")?.report)
    }

    @Test fun usageRecordedTaskIsMarkedInterruptedWithoutDuplicateRecoveryCallback() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-usage-done", agentId = "RESEARCHER", status = "USAGE_RECORDED",
            detail = "usage saved", timestamp = 100L, providerId = "openai", model = "gpt-4o-mini",
            attempts = 1, processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:task-usage-done:openai"
        )))
        var callbackCalled = false
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            callbackCalled = true
            false
        })
        assertFalse(callbackCalled)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun startupMarksRunningTasksInterruptedWithoutSavingPrompts() {
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-1", "tester", "RUNNING", "", 100L, "", "", 0, "previous-process"))
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-2", "analyst", "COMPLETED", "done", 200L, "p", "m", 1))
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 300L))
        val rows = TaskCheckpointStore.latest(context).associateBy { it.taskId }
        assertEquals("INTERRUPTED", rows.getValue("task-1").status)
        assertEquals("COMPLETED", rows.getValue("task-2").status)
        assertFalse(TaskCheckpointStore.load(context).toString().contains("prompt"))
    }

    @Test fun doesNotMarkCurrentProcessTaskInterruptedDuringActivityRecreation() {
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-live", "tester", "RUNNING", "", 100L, "", "", 0))
        assertEquals(0, TaskCheckpointStore.recoverStaleRunning(context, 200L))
        assertEquals("RUNNING", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun latestUsesJournalOrderWhenTimestampsCollide() {
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-1", "tester", "QUEUED", "", 200L, "", "", 0))
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-1", "tester", "RUNNING", "", 200L, "", "", 0))
        assertEquals(1, TaskCheckpointStore.latest(context).size)
        assertEquals("RUNNING", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun latestUsesJournalOrderWhenWallClockMovesBackwards() {
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-clock", "tester", "QUEUED", "", 900L, "", "", 0))
        TaskCheckpointStore.record(context, TaskCheckpointStore.Event("task-clock", "tester", "RUNNING", "", 100L, "", "", 0))
        assertEquals("RUNNING", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun recordReportsSuccessfulDurableWrite() {
        val saved = TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event("task-durable", "tester", "RUNNING", "", 100L, "", "", 0)
        )
        assertTrue(saved)
        assertEquals("RUNNING", TaskCheckpointStore.latest(context).single().status)
    }
    @Test fun lateWorkerCancellationDoesNotOverwriteCoordinatorTimeout() {
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event("task-timeout", "tester", "TIMED_OUT", "deadline", 300L, "", "", 1)
        ))
        assertTrue(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event("task-timeout", "tester", "CANCELLED", "worker interrupted", 400L, "", "", 1)
        ))
        assertEquals("TIMED_OUT", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun preferenceAccessFailureDoesNotCrashOrAllowCheckpointOverwrite() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled checkpoint preferences failure")
            }
        }
        assertFalse(TaskCheckpointStore.record(
            failingContext,
            TaskCheckpointStore.Event("task-unreadable", "tester", "RUNNING", "", 100L, "", "", 0)
        ))
        assertTrue(TaskCheckpointStore.load(failingContext).isEmpty())
        assertEquals(0, TaskCheckpointStore.recoverStaleRunning(failingContext))
        assertFalse(TaskCheckpointStore.clear(failingContext))
    }

    @Test fun corruptJournalIsNotSilentlyReplacedByAnEmptyHistory() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val corrupt = """{"events":[broken"""
        assertTrue(prefs.edit().putString("events", corrupt).commit())

        assertFalse(
            TaskCheckpointStore.record(
                context,
                TaskCheckpointStore.Event("task-after-corruption", "tester", "RUNNING", "", 500L, "", "", 0)
            )
        )
        assertEquals(corrupt, prefs.getString("events", null))
        assertTrue(TaskCheckpointStore.load(context).isEmpty())
    }

    @Test fun negativePersistedUsageEstimateMakesJournalUnreadableAndPreventsOverwrite() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val malformed = """[{"task":"bad-estimate","status":"REQUESTING","estimatedInputTokens":-10}]"""
        assertTrue(prefs.edit().putString("events", malformed).commit())
        assertFalse(TaskCheckpointStore.record(
            context, TaskCheckpointStore.Event("new-task", "tester", "RUNNING", "", 700L, "", "", 0)
        ))
        assertEquals(malformed, prefs.getString("events", null))
    }

    @Test fun malformedJournalRowAlsoBlocksAppendInsteadOfDroppingUnknownData() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val malformed = """[{"task":"valid-row"},{"agent":"missing-task-id"}]"""
        assertTrue(prefs.edit().putString("events", malformed).commit())

        assertFalse(
            TaskCheckpointStore.record(
                context,
                TaskCheckpointStore.Event("task-new", "tester", "RUNNING", "", 600L, "", "", 0)
            )
        )
        assertEquals(malformed, prefs.getString("events", null))
    }


    @Test fun usagePendingStatePreservesRequestMetadataForRestartReconciliation() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-metadata-preserve", agentId = "RESEARCHER", status = "REQUESTING",
            detail = "request started", timestamp = 100L, providerId = "provider-a", model = "model-a",
            attempts = 1, processId = "previous-process", estimatedInputTokens = 450L,
            estimatedOutputTokens = 1200L, idempotencyKey = "team:task-metadata-preserve:provider-a",
            baseUrl = "https://api.example"
        )))
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-metadata-preserve", agentId = "RESEARCHER", status = "USAGE_PENDING",
            detail = "usage write failed", timestamp = 200L, providerId = "provider-a", model = "model-a",
            attempts = 1, processId = "previous-process"
        )))

        val latest = TaskCheckpointStore.latest(context).single()
        assertEquals("USAGE_PENDING", latest.status)
        assertEquals(450L, latest.estimatedInputTokens)
        assertEquals(1200L, latest.estimatedOutputTokens)
        assertEquals("team:task-metadata-preserve:provider-a", latest.idempotencyKey)
        assertEquals("https://api.example", latest.baseUrl)

        var recovered: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 300L) {
            recovered = it
            true
        })
        assertEquals(450L, recovered?.estimatedInputTokens)
        assertEquals(1200L, recovered?.estimatedOutputTokens)
        assertEquals("team:task-metadata-preserve:provider-a", recovered?.idempotencyKey)
        assertEquals("https://api.example", recovered?.baseUrl)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }


    @Test fun automaticUsageRecoveryRequiresCompleteRequestMetadata() {
        val incomplete = TaskCheckpointStore.Event(
            taskId = "legacy-request", agentId = "RESEARCHER", status = "USAGE_PENDING",
            detail = "", timestamp = 1L, providerId = "provider-a", model = "model-a",
            attempts = 1, processId = "old", estimatedInputTokens = 0L,
            estimatedOutputTokens = 1200L, idempotencyKey = ""
        )
        assertFalse(TaskCheckpointStore.hasRecoverableUsageMetadata(incomplete))
        assertFalse(TaskCheckpointStore.hasRecoverableUsageMetadata(incomplete.copy(estimatedInputTokens = 450L)))
        assertFalse(TaskCheckpointStore.hasRecoverableUsageMetadata(incomplete.copy(
            estimatedInputTokens = 450L, idempotencyKey = "team:legacy-request:provider-a", model = ""
        )))
        assertTrue(TaskCheckpointStore.hasRecoverableUsageMetadata(incomplete.copy(
            estimatedInputTokens = 450L, idempotencyKey = "team:legacy-request:provider-a"
        )))
    }

}
