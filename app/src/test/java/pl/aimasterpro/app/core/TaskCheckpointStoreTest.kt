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
        TaskCheckpointStore.resetStorageHealthForTests()
    }

    @Test fun sharedPreferenceQuarantineAlsoBlocksCheckpointRecoveryAndMutation() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val original = "[]"
        assertTrue(prefs.edit().putString("events", original).commit())
        val failingEditor = object : android.content.SharedPreferences.Editor by prefs.edit().putString("events", "[ambiguous]") {
            override fun commit(): Boolean = false
        }
        assertFalse(SharedPreferencesWriteHealth.commit("task_checkpoints_v1", failingEditor))

        assertTrue(TaskCheckpointStore.isStorageUncertain())
        assertFalse(TaskCheckpointStore.isJournalReadable(context))
        assertTrue(TaskCheckpointStore.load(context).isEmpty())
        assertFalse(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "blocked-task", agentId = "MASTER", status = "QUEUED", detail = "",
            timestamp = 300L, providerId = "", model = "", attempts = 0
        )))
        assertFalse(TaskCheckpointStore.clear(context))
        assertEquals(original, prefs.getString("events", null))
    }

    @Test fun checkpointReaderRejectsCoercedNumericStringsWithoutRewritingJournal() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"task":"task-corrupt-checkpoint","agent":"RESEARCHER","status":"REQUESTING","detail":"started","ts":100,"provider":"openai","model":"gpt-4o-mini","attempts":1,"estimatedInputTokens":"450","estimatedOutputTokens":1200,"idempotencyKey":"request-key"}]"""
        assertTrue(prefs.edit().putString("events", corrupt).commit())

        assertFalse("Numeric strings must not be coerced into trusted recovery metadata",
            TaskCheckpointStore.isJournalReadable(context))
        assertFalse(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "new-task", agentId = "MASTER", status = "QUEUED", detail = "",
            timestamp = 200L, providerId = "", model = "", attempts = 0
        )))
        assertEquals("Corrupt recovery evidence must remain intact for diagnosis",
            corrupt, prefs.getString("events", null))
    }

    @Test fun ambiguousCheckpointCommitQuarantinesJournalUntilProcessRecovery() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val failingPrefs = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "edit" -> {
                    val realEditor = prefs.edit()
                    lateinit var editorProxy: android.content.SharedPreferences.Editor
                    editorProxy = java.lang.reflect.Proxy.newProxyInstance(
                        android.content.SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java)
                    ) { _, editorMethod, editorArgs ->
                        when (editorMethod.name) {
                            "putString" -> {
                                realEditor.putString(editorArgs!![0] as String, editorArgs[1] as String)
                                editorProxy
                            }
                            "commit" -> {
                                realEditor.apply()
                                false
                            }
                            "apply" -> { realEditor.apply(); null }
                            "toString" -> "AmbiguousCheckpointEditor"
                            else -> editorMethod.invoke(realEditor, *(editorArgs ?: emptyArray()))
                        }
                    } as android.content.SharedPreferences.Editor
                    editorProxy
                }
                "getString" -> prefs.getString(args!![0] as String, args[1] as String?)
                "contains" -> prefs.contains(args!![0] as String)
                "getAll" -> prefs.all
                "toString" -> "AmbiguousCheckpointPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> method.invoke(prefs, *(args ?: emptyArray()))
            }
        } as android.content.SharedPreferences
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                if (name == "task_checkpoints_v1") failingPrefs else super.getSharedPreferences(name, mode)
        }

        assertFalse(TaskCheckpointStore.record(
            failingContext,
            TaskCheckpointStore.Event("ambiguous-write", "MASTER", "RUNNING", "", 100L, "", "", 0)
        ))
        assertTrue(TaskCheckpointStore.isStorageUncertain())
        assertFalse(TaskCheckpointStore.isJournalReadable(context))
        assertTrue(TaskCheckpointStore.load(context).isEmpty())
        assertEquals(0, TaskCheckpointStore.recoverStaleRunning(context, 200L))
        assertFalse(TaskCheckpointStore.record(
            context,
            TaskCheckpointStore.Event("must-not-append", "MASTER", "RUNNING", "", 300L, "", "", 0)
        ))
        assertFalse(TaskCheckpointStore.clear(context))
        assertTrue(prefs.getString("events", null)?.contains("ambiguous-write") == true)
    }

    @Test fun safeCheckpointBaseUrlPreservesExplicitPortAndExcludesSensitiveUrlParts() {
        assertEquals(
            "http://127.0.0.1:11434",
            TaskCheckpointStore.safeBaseUrlForCheckpoint("http://user:secret@127.0.0.1:11434/v1/chat/completions?key=hidden")
        )
        assertEquals(
            "https://api.example.com",
            TaskCheckpointStore.safeBaseUrlForCheckpoint("https://api.example.com/v1?token=hidden")
        )
        assertEquals("", TaskCheckpointStore.safeBaseUrlForCheckpoint("not a valid URL"))
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

    @Test fun unreadableResultArchivePreservesPendingCheckpointsForLaterRecovery() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-archive-unreadable-request", agentId = "RESEARCHER", status = "REQUESTING",
            detail = "request may already have completed", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:task-archive-unreadable-request:openai"
        )))
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-archive-unreadable-result", agentId = "DEVELOPER", status = "RESULT_PERSIST_PENDING",
            detail = "archive write may have committed", timestamp = 110L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 220L,
            estimatedOutputTokens = 520L, idempotencyKey = "team:task-archive-unreadable-result:openai"
        )))
        val corruptArchive = """[{"taskId":"broken","agentId":"DEVELOPER","report":"saved","providerId":"openai","model":"gpt-4o-mini","createdAt":"invalid"}]"""
        assertTrue(context.getSharedPreferences("task_result_archive_v1", Context.MODE_PRIVATE)
            .edit().putString("entries", corruptArchive).commit())
        assertFalse(TaskResultArchive.isArchiveReadable(context))

        var usageRecoveryCalled = false
        assertEquals(0, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            usageRecoveryCalled = true
            true
        })
        assertFalse(usageRecoveryCalled)
        val latest = TaskCheckpointStore.latest(context).associateBy { it.taskId }
        assertEquals("REQUESTING", latest["task-archive-unreadable-request"]?.status)
        assertEquals("RESULT_PERSIST_PENDING", latest["task-archive-unreadable-result"]?.status)
        assertEquals(corruptArchive, context.getSharedPreferences("task_result_archive_v1", Context.MODE_PRIVATE)
            .getString("entries", null))
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

    @Test fun archivedReportBehindTerminalTimeoutRetriesUsageAccountingAfterRestart() {
        val taskId = "task-timeout-restart-archive"
        val timedOut = TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "TIMED_OUT",
            detail = "coordinator deadline elapsed", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 240L,
            estimatedOutputTokens = 640L, idempotencyKey = "team:$taskId:openai",
            baseUrl = "https://api.openai.com"
        )
        assertTrue(TaskCheckpointStore.record(context, timedOut))
        assertTrue(TaskResultArchive.put(
            context, taskId, "RESEARCHER", "Report committed before process death.",
            "openai", "gpt-4o-mini", 150L, "chat-timeout-restart"
        ))

        var firstRecovery: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            firstRecovery = it
            false
        })
        assertEquals("TIMED_OUT", firstRecovery?.status)
        assertEquals("INTERRUPTED_USAGE_PENDING", TaskCheckpointStore.latest(context).single().status)
        assertEquals("team:$taskId:openai", TaskCheckpointStore.latest(context).single().idempotencyKey)

        var retryRecovery: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 300L) {
            retryRecovery = it
            true
        })
        assertEquals("INTERRUPTED_USAGE_PENDING", retryRecovery?.status)
        assertEquals("COMPLETED", TaskCheckpointStore.latest(context).single().status)
        assertEquals("Report committed before process death.", TaskResultArchive.get(context, taskId)?.report)

        var unexpectedRetry = false
        assertEquals(0, TaskCheckpointStore.recoverStaleRunning(context, 400L) {
            unexpectedRetry = true
            true
        })
        assertFalse("A completed archived task must not be reconciled on every app start", unexpectedRetry)
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

    @Test fun timedOutRequestWithoutArchivedReportStillReconcilesUnknownUsage() {
        val taskId = "task-timeout-no-report-reconcile"
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "TIMED_OUT",
            detail = "coordinator deadline elapsed", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 210L,
            estimatedOutputTokens = 510L, idempotencyKey = "team:$taskId:openai"
        )))

        var recovered: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            recovered = it
            true
        })
        assertEquals("TIMED_OUT", recovered?.status)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun timedOutRequestWithoutReportKeepsRetryablePendingStateWhenUsageWriteFails() {
        val taskId = "task-timeout-no-report-pending"
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "TIMED_OUT",
            detail = "coordinator deadline elapsed", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 230L,
            estimatedOutputTokens = 530L, idempotencyKey = "team:$taskId:openai"
        )))

        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) { false })
        assertEquals("INTERRUPTED_USAGE_PENDING", TaskCheckpointStore.latest(context).single().status)
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 300L) { true })
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun cancelledInFlightRequestWithoutReportStillReconcilesUnknownUsage() {
        val taskId = "task-cancelled-in-flight"
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "CANCELLED",
            detail = "worker cancelled after request start", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 220L,
            estimatedOutputTokens = 520L, idempotencyKey = "team:$taskId:openai"
        )))

        var recovered: TaskCheckpointStore.Event? = null
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            recovered = it
            true
        })
        assertEquals("CANCELLED", recovered?.status)
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun cancelledQueuedTaskWithoutRequestMetadataDoesNotTriggerUsageRecovery() {
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "task-cancelled-before-request", agentId = "RESEARCHER", status = "CANCELLED",
            detail = "cancelled while queued", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 0,
            processId = "previous-process"
        )))

        var recoveryCalled = false
        assertEquals(0, TaskCheckpointStore.recoverStaleRunning(context, 200L) {
            recoveryCalled = true
            true
        })
        assertFalse(recoveryCalled)
        assertEquals("CANCELLED", TaskCheckpointStore.latest(context).single().status)
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
    @Test fun requestingTaskCannotBeFinalizedBeforeUsageCheckpointIsDurable() {
        val requesting = TaskCheckpointStore.Event(
            taskId = "task-unresolved-request", agentId = "RESEARCHER", status = "REQUESTING",
            detail = "provider POST may have been processed", timestamp = 100L,
            providerId = "openai", model = "gpt-4o-mini", attempts = 1,
            processId = "previous-process", estimatedInputTokens = 200L,
            estimatedOutputTokens = 500L, idempotencyKey = "team:task-unresolved-request:openai"
        )
        assertTrue(TaskCheckpointStore.record(context, requesting))

        // A later UI/worker callback must not mask a failed usage checkpoint write.
        assertTrue(TaskCheckpointStore.record(context, requesting.copy(status = "COMPLETED", processId = "")))
        assertTrue(TaskCheckpointStore.record(context, requesting.copy(status = "FAILED", processId = "")))
        assertEquals("REQUESTING", TaskCheckpointStore.latest(context).single().status)

        // Startup is allowed to close the request only after idempotent usage reconciliation.
        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) { true })
        assertEquals("INTERRUPTED", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun durableUsageCheckpointAllowsTaskFinalization() {
        val requesting = TaskCheckpointStore.Event(
            taskId = "task-usage-confirmed", agentId = "RESEARCHER", status = "REQUESTING",
            detail = "provider POST", timestamp = 100L, providerId = "openai",
            model = "gpt-4o-mini", attempts = 1, processId = "previous-process",
            estimatedInputTokens = 200L, estimatedOutputTokens = 500L,
            idempotencyKey = "team:task-usage-confirmed:openai"
        )
        assertTrue(TaskCheckpointStore.record(context, requesting))
        assertTrue(TaskCheckpointStore.record(context, requesting.copy(
            status = "USAGE_RECORDED", detail = "usage committed"
        )))
        assertTrue(TaskCheckpointStore.record(context, requesting.copy(
            status = "COMPLETED", detail = "result committed", processId = ""
        )))
        assertEquals("COMPLETED", TaskCheckpointStore.latest(context).single().status)
    }

    @Test fun startupMayCompleteRequestingTaskWhenItsReportIsArchived() {
        val taskId = "task-requesting-archived-report"
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = taskId, agentId = "RESEARCHER", status = "REQUESTING",
            detail = "provider POST", timestamp = 100L, providerId = "openai",
            model = "gpt-4o-mini", attempts = 1, processId = "previous-process",
            estimatedInputTokens = 200L, estimatedOutputTokens = 500L,
            idempotencyKey = "team:$taskId:openai"
        )))
        assertTrue(TaskResultArchive.put(
            context, taskId, "RESEARCHER", "Durable report.", "openai",
            "gpt-4o-mini", 150L, "chat-recovery"
        ))

        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 200L) { true })
        assertEquals("COMPLETED", TaskCheckpointStore.latest(context).single().status)
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

    @Test fun journalHealthDistinguishesEmptyReadableJournalFromCorruptData() {
        assertTrue(TaskCheckpointStore.isJournalReadable(context))
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"task":"broken"}"""
        assertTrue(prefs.edit().putString("events", corrupt).commit())
        assertFalse(TaskCheckpointStore.isJournalReadable(context))
        assertEquals(corrupt, prefs.getString("events", null))
    }

    @Test fun journalHealthReportsPreferenceAccessFailure() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled checkpoint journal read failure")
            }
        }
        assertFalse(TaskCheckpointStore.isJournalReadable(failingContext))
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


    @Test fun checkpointWriterRejectsInvalidEnvelopeWithoutCorruptingJournal() {
        val invalidEvents = listOf(
            TaskCheckpointStore.Event("blank-agent", "", "RUNNING", "", 100L, "", "", 0),
            TaskCheckpointStore.Event("blank-status", "RESEARCHER", " ", "", 100L, "", "", 0),
            TaskCheckpointStore.Event("negative-time", "RESEARCHER", "RUNNING", "", -1L, "", "", 0),
            TaskCheckpointStore.Event("negative-attempts", "RESEARCHER", "RUNNING", "", 100L, "", "", -1),
            TaskCheckpointStore.Event("negative-input", "RESEARCHER", "RUNNING", "", 100L, "", "", 0, estimatedInputTokens = -1L)
        )
        invalidEvents.forEach { event ->
            assertFalse("Invalid checkpoint envelope must not be persisted: ${event.taskId}",
                TaskCheckpointStore.record(context, event))
            assertTrue(TaskCheckpointStore.isJournalReadable(context))
            assertTrue(TaskCheckpointStore.latest(context).isEmpty())
        }
    }

    @Test fun journalRejectsBlankAgentAndLifecycleStatus() {
        val prefs = context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE)
        val malformedRows = listOf(
            """[{"task":"blank-agent","agent":"","status":"RUNNING","detail":"","ts":100,"provider":"","model":"","attempts":0}]""",
            """[{"task":"blank-status","agent":"RESEARCHER","status":"","detail":"","ts":100,"provider":"","model":"","attempts":0}]"""
        )
        malformedRows.forEach { malformed ->
            assertTrue(prefs.edit().putString("events", malformed).commit())
            assertFalse(TaskCheckpointStore.isJournalReadable(context))
            assertFalse(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
                "after-invalid-row", "RESEARCHER", "QUEUED", "", 200L, "", "", 0
            )))
            assertEquals(malformed, prefs.getString("events", null))
            prefs.edit().clear().commit()
        }
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
