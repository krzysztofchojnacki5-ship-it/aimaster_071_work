package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TaskResultArchiveTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("task_result_archive_v1", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("task_result_archive_v1")
        context.getSharedPreferences("chat_state", Context.MODE_PRIVATE).edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("chat_state")
    }

    private fun createChat(chatId: String) {
        val root = JSONObject().put("currentId", chatId).put(
            "chats", JSONObject().put(chatId, JSONObject()
                .put("title", "Recovery")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "Question")))
                .put("agentActivity", JSONArray()))
        )
        assertTrue(ChatHistoryStore.writeRoot(context, root))
    }

    @Test fun queueExecutionArchiveIdsAreStableAndIsolatedByAgentAndRun() {
        val first = TaskResultArchive.executionTaskId("task-parent-123", "researcher", 1)
        assertEquals("q-task-parent-123-researcher-r1", first)
        assertEquals(first, TaskResultArchive.executionTaskId("task-parent-123", "researcher", 1))
        assertNotEquals(first, TaskResultArchive.executionTaskId("task-parent-123", "developer", 1))
        assertNotEquals(first, TaskResultArchive.executionTaskId("task-parent-123", "researcher", 2))
        assertTrue(runCatching { TaskResultArchive.executionTaskId("", "researcher", 1) }.isFailure)
        assertTrue(runCatching { TaskResultArchive.executionTaskId("task-parent", "researcher", 0) }.isFailure)
    }

    @Test fun successfulReportIsDurableAndAddressableByTaskId() {
        assertTrue(TaskResultArchive.put(
            context, "task-1", "developer", "Pełny raport agenta.", "provider-a", "model-a", 1_000L
        ))
        assertEquals(
            TaskResultArchive.Entry("task-1", "developer", "Pełny raport agenta.", "provider-a", "model-a", 1_000L),
            TaskResultArchive.get(context, "task-1")
        )
    }

    @Test fun writingSameTaskReplacesItsEntryWithoutDuplicatingIt() {
        assertTrue(TaskResultArchive.put(context, "task-1", "developer", "Old report", "provider-a", "model-a", 1_000L))
        assertTrue(TaskResultArchive.put(context, "task-2", "reviewer", "Other report", "provider-b", "model-b", 2_000L))
        assertTrue(TaskResultArchive.put(context, "task-1", "developer", "Updated report", "provider-c", "model-c", 3_000L))
        val entries = TaskResultArchive.latest(context)
        assertEquals(2, entries.size)
        assertEquals("Updated report", TaskResultArchive.get(context, "task-1")?.report)
        assertEquals("task-1", entries.first().taskId)
    }

    @Test fun archivedReportsRedactCommonProviderCredentials() {
        val report = "API key: sk-12345678901234567890 and Bearer abcdefghijklmnopqrstuvwxyz123456"
        assertTrue(TaskResultArchive.put(context, "secret-task", "developer", report, "provider-a", "model-a", 1_000L))
        val stored = prefs.getString("entries", null).orEmpty()
        assertFalse(stored.contains("sk-12345678901234567890"))
        assertFalse(stored.contains("abcdefghijklmnopqrstuvwxyz123456"))
        assertTrue(TaskResultArchive.get(context, "secret-task")?.report?.contains("[REDACTED]") == true)
    }

    @Test fun emptyArchiveIsReadableAndDistinguishedFromCorruption() {
        assertTrue(TaskResultArchive.isArchiveReadable(context))
        assertTrue(TaskResultArchive.latest(context).isEmpty())
    }

    @Test fun corruptArchiveIsNotReplacedByAnEmptyArchive() {
        val corrupt = """[{"taskId":"task-1","agentId":"developer","report":"saved","providerId":"p","model":"m","createdAt":1000},null]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())
        assertFalse(TaskResultArchive.isArchiveReadable(context))
        assertNull(TaskResultArchive.get(context, "missing"))
        assertFalse(TaskResultArchive.put(context, "task-2", "developer", "new report", "p", "m", 2_000L))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun fractionalArchiveTimestampIsRejectedWithoutOverwritingExistingData() {
        val corrupt = """[{"taskId":"fractional-time","agentId":"developer","report":"saved","providerId":"p","model":"m","createdAt":1000.5}]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())
        assertNull(TaskResultArchive.get(context, "fractional-time"))
        assertFalse(TaskResultArchive.put(context, "new-task", "developer", "new report", "p", "m", 2_000L))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun archivedReportIsRestoredToItsChatExactlyOnce() {
        val chatId = "chat-recovery-1"
        val root = JSONObject()
            .put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject()
                .put("title", "Recovery test")
                .put("messages", JSONArray())
                .put("agentActivity", JSONArray())))
        assertTrue(ChatHistoryStore.writeRoot(context, root))
        assertTrue(TaskResultArchive.put(
            context, "task-recovery-1", "RESEARCHER", "Pełny raport odzyskany po restarcie.",
            "provider-a", "model-a", 1_000L, chatId
        ))

        assertEquals(1, TaskResultArchive.restoreArchivedReports(context))
        val activity = ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(1, activity.length())
        assertTrue(activity.optString(0).contains("[TASK_REPORT_ID:task-recovery-1]"))
        assertTrue(activity.optString(0).contains("Pełny raport odzyskany po restarcie."))

        assertEquals(0, TaskResultArchive.restoreArchivedReports(context))
        val afterRetry = ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(1, afterRetry.length())
    }

    @Test fun recoveryDoesNotDuplicateReportAlreadyPersistedInChatActivity() {
        val chatId = "chat-already-has-report"
        val report = "Raport zapisany przed przerwaniem procesu."
        val activity = JSONArray().put("[11:00] RESEARCHER ➔ MASTER [REPORT]:\n$report")
        val root = JSONObject()
            .put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject()
                .put("title", "Existing report")
                .put("messages", JSONArray())
                .put("agentActivity", activity)))
        assertTrue(ChatHistoryStore.writeRoot(context, root))
        assertTrue(TaskResultArchive.put(
            context, "task-existing-report", "RESEARCHER", report,
            "provider-a", "model-a", 1_000L, chatId
        ))

        assertEquals(0, TaskResultArchive.restoreArchivedReports(context))
        val savedActivity = ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(1, savedActivity.length())
    }

    @Test fun multipleArchivedReportsRestoreChronologicallyAndKeepNewestAtActivityLimit() {
        val chatId = "chat-recovery-order"
        val existingActivity = JSONArray()
        repeat(150) { existingActivity.put("existing-$it") }
        val root = JSONObject()
            .put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject()
                .put("title", "Recovery order")
                .put("messages", JSONArray())
                .put("agentActivity", existingActivity)))
        assertTrue(ChatHistoryStore.writeRoot(context, root))

        assertTrue(TaskResultArchive.put(
            context, "task-older", "RESEARCHER", "Older archived report",
            "provider-a", "model-a", 1_000L, chatId
        ))
        assertTrue(TaskResultArchive.put(
            context, "task-newer", "DEVELOPER", "Newer archived report",
            "provider-b", "model-b", 2_000L, chatId
        ))

        assertEquals(2, TaskResultArchive.restoreArchivedReports(context))
        val activity = ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(150, activity.length())
        assertEquals("existing-2", activity.optString(0))
        assertTrue(activity.optString(148).contains("[TASK_REPORT_ID:task-older]"))
        assertTrue(activity.optString(149).contains("[TASK_REPORT_ID:task-newer]"))
    }

    @Test fun identicalReportTextFromDifferentTasksIsRestoredForBothTaskIds() {
        val chatId = "chat-identical-reports"
        val root = JSONObject()
            .put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject()
                .put("title", "Identical reports")
                .put("messages", JSONArray())
                .put("agentActivity", JSONArray())))
        assertTrue(ChatHistoryStore.writeRoot(context, root))

        val sameReport = "Analiza zakończona: brak dodatkowych ustaleń."
        assertTrue(TaskResultArchive.put(
            context, "task-identical-a", "RESEARCHER", sameReport,
            "provider-a", "model-a", 1_000L, chatId
        ))
        assertTrue(TaskResultArchive.put(
            context, "task-identical-b", "REVIEWER", sameReport,
            "provider-b", "model-b", 2_000L, chatId
        ))

        assertEquals(2, TaskResultArchive.restoreArchivedReports(context))
        val activity = ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(2, activity.length())
        assertTrue(activity.optString(0).contains("[TASK_REPORT_ID:task-identical-a]"))
        assertTrue(activity.optString(1).contains("[TASK_REPORT_ID:task-identical-b]"))
        assertEquals(0, TaskResultArchive.restoreArchivedReports(context))
    }

    @Test fun legacyArchiveWithoutChatIdIsNotAttachedToAnUnrelatedConversation() {
        val chatId = "chat-unrelated"
        val root = JSONObject()
            .put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject()
                .put("title", "Unrelated")
                .put("messages", JSONArray())
                .put("agentActivity", JSONArray())))
        assertTrue(ChatHistoryStore.writeRoot(context, root))
        assertTrue(TaskResultArchive.put(
            context, "legacy-task", "RESEARCHER", "Legacy report", "provider-a", "model-a", 1_000L
        ))

        assertEquals(0, TaskResultArchive.restoreArchivedReports(context))
        val activity = ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(0, activity.length())
    }

    @Test fun restoreSurfacesCorruptArchiveInsteadOfReportingSuccessfulNoOp() {
        val corrupt = """[{"taskId":"task-1","agentId":"developer","report":"saved","providerId":"p","model":"m","createdAt":"invalid"}]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())

        assertTrue(runCatching { TaskResultArchive.restoreArchivedReports(context) }.isFailure)
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun restoreSurfacesUnreadableChatHistoryInsteadOfDroppingArchivedReport() {
        val chatId = "chat-corrupt-during-recovery"
        val report = "Durable report must remain recoverable."
        assertTrue(TaskResultArchive.put(
            context, "task-preserve-on-chat-error", "RESEARCHER", report,
            "provider-a", "model-a", 1_000L, chatId
        ))
        val chatPrefs = context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
        val corruptHistory = """{"chats":{"broken":{"messages":[null]}}}"""
        assertTrue(chatPrefs.edit().putString("chats_v2", corruptHistory).commit())

        assertTrue(runCatching { TaskResultArchive.restoreArchivedReports(context) }.isFailure)
        assertEquals(report, TaskResultArchive.get(context, "task-preserve-on-chat-error")?.report)
        assertEquals(corruptHistory, chatPrefs.getString("chats_v2", null))
    }

    @Test fun preferenceAccessFailureDoesNotPretendArchiveIsEmptyOrAcceptNewReport() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled result archive preferences failure")
            }
        }
        assertFalse(TaskResultArchive.isArchiveReadable(failingContext))
        assertNull(TaskResultArchive.get(failingContext, "task-1"))
        assertTrue(TaskResultArchive.latest(failingContext).isEmpty())
        assertFalse(TaskResultArchive.put(
            failingContext, "task-new", "developer", "New report", "provider-a", "model-a", 2_000L
        ))
    }

    @Test fun blankAndOversizedReportsAreRejected() {
        assertFalse(TaskResultArchive.put(context, "blank", "developer", " ", "p", "m", 1_000L))
        assertFalse(TaskResultArchive.put(context, "large", "developer", "x".repeat(32_001), "p", "m", 1_000L))
        assertNull(TaskResultArchive.get(context, "blank"))
        assertNull(TaskResultArchive.get(context, "large"))
    }

    @Test fun ambiguousArchiveCommitQuarantinesRecoveryReadsAndWrites() {
        val failing = AmbiguousCommitContext(context, "task_result_archive_v1")
        assertFalse(TaskResultArchive.put(
            failing, "ambiguous-task", "developer", "Report with uncertain durability.", "p", "m", 3_000L
        ))
        assertFalse(TaskResultArchive.isArchiveReadable(context))
        assertTrue(runCatching { TaskResultArchive.restoreArchivedReports(context) }.isFailure)
        assertNull(TaskResultArchive.get(context, "ambiguous-task"))
        assertFalse(TaskResultArchive.put(context, "second-task", "developer", "blocked", "p", "m", 4_000L))
    }
    @Test fun archivePutWaitsForSharedPreferenceLockAcrossReadModifyWrite() {
        val ready = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            ready.countDown()
            result.set(TaskResultArchive.put(
                context, "task-locked-archive", "RESEARCHER", "Atomic archive transaction.",
                "provider", "model", 2_000L, "chat-locked"
            ))
            finished.countDown()
        }
        SharedPreferencesWriteHealth.withHealthyAccess("task_result_archive_v1") {
            worker.start()
            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals("Archive put must block on the shared preference-file lock",
                Thread.State.BLOCKED, worker.state)
            assertFalse("Archive put must not finish while its preference file is locked",
                finished.count == 0L)
        }
        worker.join(3_000L)
        assertTrue(finished.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(result.get())
        assertEquals("task-locked-archive", TaskResultArchive.latest(context).single().taskId)
    }


@Test fun archivedReportSurvivesHistoryCommitFailureBeforeApplyAndReplaysAfterRestart() {
        val chatId = "task-report-before-apply"
        createChat(chatId)
        assertTrue(TaskResultArchive.put(
            context, "report-before-apply", "RESEARCHER", "Recover this report.",
            "provider-a", "model-a", 22_000L, chatId
        ))
        val failing = AmbiguousCommitContext(context, "chat_state", applyBeforeFailure = false)
        assertTrue(runCatching { TaskResultArchive.restoreArchivedReports(failing) }.isFailure)
        assertNotNull(TaskResultArchive.get(context, "report-before-apply"))

        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertEquals(1, TaskResultArchive.restoreArchivedReports(context))
        assertEquals(0, TaskResultArchive.restoreArchivedReports(context))
        val rows = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats")
            .getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(1, (0 until rows.length()).count { rows.getString(it).contains("[TASK_REPORT_ID:report-before-apply]") })
    }

    @Test fun archivedReportAppliedBeforeAmbiguousFailureIsNotDuplicatedAfterRestart() {
        val chatId = "task-report-after-apply"
        createChat(chatId)
        assertTrue(TaskResultArchive.put(
            context, "report-after-apply", "RESEARCHER", "Already applied report.",
            "provider-a", "model-a", 23_000L, chatId
        ))
        val failing = AmbiguousCommitContext(context, "chat_state", applyBeforeFailure = true)
        assertTrue(runCatching { TaskResultArchive.restoreArchivedReports(failing) }.isFailure)
        assertNotNull(TaskResultArchive.get(context, "report-after-apply"))

        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertEquals(0, TaskResultArchive.restoreArchivedReports(context))
        val rows = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats")
            .getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(1, (0 until rows.length()).count { rows.getString(it).contains("[TASK_REPORT_ID:report-after-apply]") })
    }


    @Test fun archiveReaderRejectsRowsBeyondCapacityWithoutRewritingData() {
        val rows = JSONArray()
        repeat(81) { index ->
            rows.put(JSONObject()
                .put("taskId", "overflow-task-$index")
                .put("agentId", "RESEARCHER")
                .put("report", "Report $index")
                .put("providerId", "provider-a")
                .put("model", "model-a")
                .put("createdAt", 40_000L + index))
        }
        val corrupt = rows.toString()
        assertTrue(prefs.edit().putString("entries", corrupt).commit())

        assertFalse(TaskResultArchive.isArchiveReadable(context))
        assertFalse(TaskResultArchive.put(
            context, "new-task", "RESEARCHER", "New report", "provider-a", "model-a", 50_000L
        ))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun putRejectsOversizedArchiveMetadataAndChatIdsBeforeWriting() {
        assertFalse(TaskResultArchive.put(context, "t".repeat(513), "RESEARCHER", "Report", "p", "m", 1_000L))
        assertFalse(TaskResultArchive.put(context, "task-agent", "a".repeat(121), "Report", "p", "m", 1_000L))
        assertFalse(TaskResultArchive.put(context, "task-provider", "RESEARCHER", "Report", "p".repeat(201), "m", 1_000L))
        assertFalse(TaskResultArchive.put(context, "task-model", "RESEARCHER", "Report", "p", "m".repeat(241), 1_000L))
        assertFalse(TaskResultArchive.put(context, "task-chat", "RESEARCHER", "Report", "p", "m", 1_000L, "c".repeat(201)))
        assertTrue(TaskResultArchive.latest(context).isEmpty())
    }

    @Test fun archiveReaderRejectsOversizedMetadataAndChatIdWithoutRewritingData() {
        val corrupt = JSONArray().put(JSONObject()
            .put("taskId", "task-metadata")
            .put("agentId", "RESEARCHER")
            .put("report", "Saved report")
            .put("providerId", "provider-a")
            .put("model", "m".repeat(241))
            .put("createdAt", 1_000L)
            .put("chatId", "chat")).toString()
        assertTrue(prefs.edit().putString("entries", corrupt).commit())

        assertFalse(TaskResultArchive.isArchiveReadable(context))
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun archiveReaderRejectsOversizedRawPayloadBeforeParsingAndPreservesIt() {
        val oversized = " ".repeat(3_000_001)
        assertTrue(prefs.edit().putString("entries", oversized).commit())

        assertFalse(TaskResultArchive.isArchiveReadable(context))
        assertFalse(TaskResultArchive.put(
            context, "new-task", "RESEARCHER", "New report", "provider-a", "model-a", 50_000L
        ))
        assertEquals(oversized, prefs.getString("entries", null))
    }

}
