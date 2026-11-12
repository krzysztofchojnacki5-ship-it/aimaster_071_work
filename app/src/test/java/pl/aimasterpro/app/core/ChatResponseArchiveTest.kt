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
class ChatResponseArchiveTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val archivePrefs get() = context.getSharedPreferences("chat_response_archive_v1", Context.MODE_PRIVATE)

    @Before fun reset() {
        archivePrefs.edit().clear().commit()
        context.getSharedPreferences("chat_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("task_checkpoints_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("task_result_archive_v1", Context.MODE_PRIVATE).edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("task_result_archive_v1")
        SharedPreferencesWriteHealth.resetForTests("chat_response_archive_v1")
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        TaskCheckpointStore.resetStorageHealthForTests()
    }

    private fun createChat(chatId: String) {
        val root = JSONObject().put("currentId", chatId).put(
            "chats", JSONObject().put(chatId, JSONObject()
                .put("title", "Recovery")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "Question")))
                .put("assistantTaskIds", JSONArray()))
        )
        assertTrue(ChatHistoryStore.writeRoot(context, root))
    }

    @Test fun archivedAnswerIsRestoredIntoOriginalChatExactlyOnce() {
        val chatId = "chat-response-1"
        createChat(chatId)
        assertTrue(ChatResponseArchive.put(
            context, "chat-task-1", chatId, "MASTER",
            "Pełna odpowiedź z API, razem z formatowaniem.\n\nDrugi akapit.",
            "provider-a", "model-a", 2_000L
        ))

        assertEquals(1, ChatResponseArchive.restoreArchivedResponses(context))
        val chat = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats").getJSONObject(chatId)
        val messages = chat.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("Pełna odpowiedź z API, razem z formatowaniem.\n\nDrugi akapit.",
            messages.getJSONObject(1).getString("text"))
        assertEquals("chat-task-1", chat.getJSONArray("assistantTaskIds").getString(0))

        assertEquals(0, ChatResponseArchive.restoreArchivedResponses(context))
        assertEquals(2, ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getJSONArray("messages").length())
    }

    @Test fun existingAssistantTaskMarkerPreventsDuplicateReplay() {
        val chatId = "chat-response-existing"
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("text", "Question"))
            .put(JSONObject().put("role", "assistant").put("text", "Already saved"))
        val root = JSONObject().put("currentId", chatId).put(
            "chats", JSONObject().put(chatId, JSONObject()
                .put("title", "Existing")
                .put("messages", messages)
                .put("assistantTaskIds", JSONArray().put("same-task")))
        )
        assertTrue(ChatHistoryStore.writeRoot(context, root))
        assertTrue(ChatResponseArchive.put(
            context, "same-task", chatId, "MASTER", "Already saved", "provider-a", "model-a", 3_000L
        ))

        assertEquals(0, ChatResponseArchive.restoreArchivedResponses(context))
        assertEquals(2, ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getJSONArray("messages").length())
    }

    @Test fun corruptArchiveIsNotReplacedOrReportedAsReadable() {
        val corrupt = """[{"taskId":"broken","chatId":"chat","agentId":"MASTER","response":"answer","providerId":"p","model":"m","createdAt":"bad"}]"""
        assertTrue(archivePrefs.edit().putString("responses", corrupt).commit())
        assertFalse(ChatResponseArchive.isArchiveReadable(context))
        assertFalse(ChatResponseArchive.put(
            context, "new-task", "chat", "MASTER", "New answer", "p", "m", 4_000L
        ))
        assertTrue(runCatching { ChatResponseArchive.restoreArchivedResponses(context) }.isFailure)
        assertEquals(corrupt, archivePrefs.getString("responses", null))
    }

    @Test fun blankAndOversizedAnswersAreRejected() {
        assertFalse(ChatResponseArchive.put(context, "blank", "chat", "MASTER", " ", "p", "m", 1_000L))
        assertFalse(ChatResponseArchive.put(context, "large", "chat", "MASTER", "x".repeat(32_001), "p", "m", 1_000L))
        assertNull(ChatResponseArchive.get(context, "blank"))
        assertNull(ChatResponseArchive.get(context, "large"))
    }

    @Test fun archivedChatAnswerAllowsCheckpointRecoveryToCompleteWithoutRepeatingApi() {
        val chatId = "chat-response-checkpoint"
        createChat(chatId)
        assertTrue(ChatResponseArchive.put(
            context, "chat-task-checkpoint", chatId, "MASTER", "Durable response",
            "provider-a", "model-a", 5_000L
        ))
        assertTrue(TaskCheckpointStore.record(context, TaskCheckpointStore.Event(
            taskId = "chat-task-checkpoint", agentId = "MASTER", status = "USAGE_RECORDED",
            detail = "usage saved", timestamp = 4_000L, providerId = "provider-a",
            model = "model-a", attempts = 1, processId = "previous-process"
        )))

        assertEquals(1, TaskCheckpointStore.recoverStaleRunning(context, 6_000L) {
            fail("Usage is already recorded; recovery must not create an UNAVAILABLE usage record.")
            true
        })
        assertEquals("COMPLETED", TaskCheckpointStore.latest(context).single().status)
        assertEquals(1, ChatResponseArchive.restoreArchivedResponses(context))
    }

    @Test fun missingOriginalChatDoesNotAttachResponseToAnotherConversation() {
        assertTrue(ChatResponseArchive.put(
            context, "orphan-task", "deleted-chat", "MASTER", "Orphan answer",
            "provider-a", "model-a", 7_000L
        ))
        createChat("different-chat")
        assertEquals(0, ChatResponseArchive.restoreArchivedResponses(context))
        assertNotNull(ChatResponseArchive.get(context, "orphan-task"))
        assertEquals(1, ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject("different-chat").getJSONArray("messages").length())
    }
    @Test fun multipleArchivedAnswersReplayInOriginalChronologicalOrder() {
        val chatId = "chat-response-sequence"
        createChat(chatId)
        assertTrue(ChatResponseArchive.put(
            context, "sequence-task-1", chatId, "MASTER", "First answer", "provider-a", "model-a", 8_000L
        ))
        assertTrue(ChatResponseArchive.put(
            context, "sequence-task-2", chatId, "MASTER", "Second answer", "provider-a", "model-a", 9_000L
        ))

        assertEquals(2, ChatResponseArchive.restoreArchivedResponses(context))
        val chat = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats").getJSONObject(chatId)
        val messages = chat.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("First answer", messages.getJSONObject(1).getString("text"))
        assertEquals("Second answer", messages.getJSONObject(2).getString("text"))
        assertEquals("sequence-task-1", chat.getJSONArray("assistantTaskIds").getString(0))
        assertEquals("sequence-task-2", chat.getJSONArray("assistantTaskIds").getString(1))
        assertEquals(0, ChatResponseArchive.restoreArchivedResponses(context))
    }

    @Test fun unreadableChatHistoryLeavesArchivedAnswerAvailableForRetry() {
        val chatId = "chat-response-corrupt-history"
        assertTrue(ChatResponseArchive.put(
            context, "history-corrupt-task", chatId, "MASTER", "Keep this answer",
            "provider-a", "model-a", 10_000L
        ))
        val corrupt = """{"chats":{"broken":"""
        assertTrue(context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
            .edit().putString("chats_v2", corrupt).commit())

        assertTrue(runCatching { ChatResponseArchive.restoreArchivedResponses(context) }.isFailure)
        assertNotNull(ChatResponseArchive.get(context, "history-corrupt-task"))
        assertEquals(corrupt, context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)
            .getString("chats_v2", null))
    }

    @Test fun concurrentArchiveWritesDoNotLoseEntries() {
        val ready = java.util.concurrent.CountDownLatch(12)
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val workers = (1..12).map { index ->
            Thread {
                ready.countDown()
                try {
                    start.await()
                    if (!ChatResponseArchive.put(
                        context, "parallel-task-$index", "parallel-chat", "MASTER",
                        "Answer $index", "provider-a", "model-a", 20_000L + index
                    )) failures += "parallel-task-$index"
                } catch (error: Throwable) {
                    failures += "parallel-task-$index: ${error.javaClass.simpleName}"
                }
            }.apply { start() }
        }
        assertTrue("Archive writers did not become ready", ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5_000L) }
        assertTrue("Archive writers did not finish", workers.none { it.isAlive })
        assertTrue("Archive writes failed: $failures", failures.isEmpty())
        val entries = ChatResponseArchive.latest(context)
        assertEquals(12, entries.size)
        assertEquals(12, entries.map { it.taskId }.toSet().size)
    }

    @Test fun archiveCapacityIsBoundedAndNewestEntriesAreRetained() {
        repeat(30) { index ->
            assertTrue(ChatResponseArchive.put(
                context, "bounded-task-$index", "bounded-chat", "MASTER",
                "Answer $index", "provider-a", "model-a", 30_000L + index
            ))
        }
        val entries = ChatResponseArchive.latest(context)
        assertEquals(25, entries.size)
        assertEquals("bounded-task-29", entries.first().taskId)
        assertEquals("bounded-task-5", entries.last().taskId)
        assertNull(ChatResponseArchive.get(context, "bounded-task-4"))
    }


@Test fun archivedAnswerSurvivesHistoryCommitFailureBeforeApplyAndReplaysAfterRestart() {
        val chatId = "chat-response-before-apply"
        createChat(chatId)
        assertTrue(ChatResponseArchive.put(
            context, "response-before-apply", chatId, "MASTER", "Recover this answer",
            "provider-a", "model-a", 20_000L
        ))
        val failing = AmbiguousCommitContext(context, "chat_state", applyBeforeFailure = false)
        assertTrue(runCatching { ChatResponseArchive.restoreArchivedResponses(failing) }.isFailure)
        assertNotNull(ChatResponseArchive.get(context, "response-before-apply"))

        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertEquals(1, ChatResponseArchive.restoreArchivedResponses(context))
        assertEquals(0, ChatResponseArchive.restoreArchivedResponses(context))
        val chat = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats").getJSONObject(chatId)
        assertEquals(2, chat.getJSONArray("messages").length())
        assertEquals("response-before-apply", chat.getJSONArray("assistantTaskIds").getString(0))
    }

    @Test fun archivedAnswerAppliedBeforeAmbiguousFailureIsNotDuplicatedAfterRestart() {
        val chatId = "chat-response-after-apply"
        createChat(chatId)
        assertTrue(ChatResponseArchive.put(
            context, "response-after-apply", chatId, "MASTER", "Already applied answer",
            "provider-a", "model-a", 21_000L
        ))
        val failing = AmbiguousCommitContext(context, "chat_state", applyBeforeFailure = true)
        assertTrue(runCatching { ChatResponseArchive.restoreArchivedResponses(failing) }.isFailure)
        assertNotNull(ChatResponseArchive.get(context, "response-after-apply"))

        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertEquals(0, ChatResponseArchive.restoreArchivedResponses(context))
        val chat = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats").getJSONObject(chatId)
        assertEquals(2, chat.getJSONArray("messages").length())
        assertEquals(1, (0 until chat.getJSONArray("assistantTaskIds").length())
            .count { chat.getJSONArray("assistantTaskIds").getString(it) == "response-after-apply" })
    }


    @Test fun ambiguousArchiveCommitQuarantinesRecoveryReadsAndWrites() {
        val failing = AmbiguousCommitContext(context, "chat_response_archive_v1")
        assertFalse(ChatResponseArchive.put(
            failing, "ambiguous-response", "chat", "MASTER",
            "Response with uncertain durability.", "provider", "model", 3_000L
        ))
        assertFalse(ChatResponseArchive.isArchiveReadable(context))
        assertTrue(runCatching { ChatResponseArchive.restoreArchivedResponses(context) }.isFailure)
        assertNull(ChatResponseArchive.get(context, "ambiguous-response"))
        assertFalse(ChatResponseArchive.put(
            context, "second-response", "chat", "MASTER", "blocked", "provider", "model", 4_000L
        ))
    }


    @Test fun preferenceAccessFailureDoesNotPretendResponseArchiveIsEmptyOrAcceptNewAnswer() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled response archive preferences failure")
            }
        }
        assertFalse(ChatResponseArchive.isArchiveReadable(failingContext))
        assertNull(ChatResponseArchive.get(failingContext, "task-1"))
        assertTrue(ChatResponseArchive.latest(failingContext).isEmpty())
        assertFalse(ChatResponseArchive.put(
            failingContext, "task-new", "chat", "MASTER", "New answer", "provider-a", "model-a", 2_000L
        ))
    }

    @Test fun archivePutWaitsForSharedPreferenceLockAcrossReadModifyWrite() {
        val ready = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            ready.countDown()
            result.set(ChatResponseArchive.put(
                context, "task-locked-response", "chat-locked", "MASTER", "Atomic response.",
                "provider", "model", 2_000L
            ))
            finished.countDown()
        }
        SharedPreferencesWriteHealth.withHealthyAccess("chat_response_archive_v1") {
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
        assertEquals("task-locked-response", ChatResponseArchive.latest(context).single().taskId)
    }

 
    @Test fun archiveReaderRejectsRowsBeyondCapacityWithoutRewritingData() {
        val rows = JSONArray()
        repeat(26) { index ->
            rows.put(JSONObject()
                .put("taskId", "overflow-task-$index")
                .put("chatId", "chat-overflow")
                .put("agentId", "MASTER")
                .put("response", "Answer $index")
                .put("providerId", "provider-a")
                .put("model", "model-a")
                .put("createdAt", 40_000L + index))
        }
        val corrupt = rows.toString()
        assertTrue(archivePrefs.edit().putString("responses", corrupt).commit())

        assertFalse(ChatResponseArchive.isArchiveReadable(context))
        assertFalse(ChatResponseArchive.put(
            context, "new-task", "chat-overflow", "MASTER", "New answer", "provider-a", "model-a", 50_000L
        ))
        assertEquals(corrupt, archivePrefs.getString("responses", null))
    }

    @Test fun putRejectsOversizedArchiveMetadataBeforeWriting() {
        assertFalse(ChatResponseArchive.put(
            context, "t".repeat(513), "chat", "MASTER", "Answer", "provider-a", "model-a", 1_000L
        ))
        assertFalse(ChatResponseArchive.put(
            context, "task-agent-long", "chat", "a".repeat(121), "Answer", "provider-a", "model-a", 1_000L
        ))
        assertFalse(ChatResponseArchive.put(
            context, "task-provider-long", "chat", "MASTER", "Answer", "p".repeat(201), "model-a", 1_000L
        ))
        assertFalse(ChatResponseArchive.put(
            context, "task-model-long", "chat", "MASTER", "Answer", "provider-a", "m".repeat(241), 1_000L
        ))
        assertTrue(ChatResponseArchive.latest(context).isEmpty())
    }

    @Test fun archiveReaderRejectsOversizedMetadataWithoutRewritingData() {
        val corrupt = JSONArray().put(JSONObject()
            .put("taskId", "task-metadata")
            .put("chatId", "chat")
            .put("agentId", "MASTER")
            .put("response", "Answer")
            .put("providerId", "provider-a")
            .put("model", "m".repeat(241))
            .put("createdAt", 1_000L)).toString()
        assertTrue(archivePrefs.edit().putString("responses", corrupt).commit())

        assertFalse(ChatResponseArchive.isArchiveReadable(context))
        assertEquals(corrupt, archivePrefs.getString("responses", null))
    }

}
