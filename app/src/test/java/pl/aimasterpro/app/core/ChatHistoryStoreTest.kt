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
class ChatHistoryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("chat_state")
    }

    @Test fun legacyMirrorImportIsAllowedOnlyWhenCanonicalDocumentIsMissing() {
        assertEquals(true, ChatHistoryStore.shouldImportLegacyMirror(context))
        assertTrue(ChatHistoryStore.writeRoot(context, JSONObject().put("chats", JSONObject())))
        assertEquals(false, ChatHistoryStore.shouldImportLegacyMirror(context))
    }

    @Test fun legacyMirrorImportFailsClosedWhenPreferenceReadFailsOrStoreIsQuarantined() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled preferences read failure")
            }
        }
        assertNull(ChatHistoryStore.shouldImportLegacyMirror(failingContext))
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertFalse(ChatHistoryStore.writeRoot(AmbiguousCommitContext(context, "chat_state"), JSONObject().put("chats", JSONObject())))
        assertTrue(SharedPreferencesWriteHealth.isUncertain("chat_state"))
        assertNull(ChatHistoryStore.shouldImportLegacyMirror(context))
    }

    @Test fun missingHistoryIsAValidEmptyStoreAndCanBeCommitted() {
        val root = ChatHistoryStore.readRoot(context)!!
        val chats = JSONObject().put("chat-1", JSONObject()
            .put("title", "Test")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "Hello"))))
        root.put("currentId", "chat-1").put("chats", chats)
        assertTrue(ChatHistoryStore.writeRoot(context, root))
        val restored = ChatHistoryStore.readRoot(context)!!
        assertEquals("chat-1", restored.optString("currentId"))
        assertEquals("Hello", restored.getJSONObject("chats").getJSONObject("chat-1")
            .getJSONArray("messages").getJSONObject(0).getString("text"))
    }

    @Test fun malformedRootIsNotInterpretedAsEmptyOrOverwritten() {
        val corrupt = """{"chats":{"broken":"""
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        assertNull(ChatHistoryStore.readRoot(context))
        assertEquals(corrupt, prefs.getString("chats_v2", null))
    }

    @Test fun malformedNestedMessageBlocksHistoryRead() {
        val corrupt = JSONObject().put("chats", JSONObject().put("chat-1", JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user"))))).toString()
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        assertNull(ChatHistoryStore.readRoot(context))
        assertEquals(corrupt, prefs.getString("chats_v2", null))
    }

    @Test fun malformedChatRowBlocksHistoryRead() {
        val corrupt = JSONObject().put("chats", JSONObject().put("chat-1", "not-an-object")).toString()
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        assertNull(ChatHistoryStore.readRoot(context))
    }

    @Test fun malformedAgentActivityOrTaskIdsBlocksHistoryRestore() {
        val corrupt = JSONObject().put("chats", JSONObject().put("chat-1", JSONObject()
            .put("messages", JSONArray())
            .put("assistantTaskIds", JSONArray().put(42)))).toString()
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        assertNull(ChatHistoryStore.readRoot(context))
        assertEquals(corrupt, prefs.getString("chats_v2", null))
    }

    @Test fun legacyMessagesAndTaskAlignmentMetadataRemainReadable() {
        val legacy = JSONObject().put("currentId", "chat-legacy")
            .put("chats", JSONObject().put("chat-legacy", JSONObject()
                .put("title", "Legacy")
                .put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("text", "Answer")))
                .put("assistantTaskIds", JSONArray().put("task-1"))
                .put("agentActivity", JSONArray().put("Agent started")))).toString()
        assertTrue(prefs.edit().putString("chats_v2", legacy).commit())
        val chat = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats").getJSONObject("chat-legacy")
        assertEquals("task-1", chat.getJSONArray("assistantTaskIds").getString(0))
        assertEquals("Agent started", chat.getJSONArray("agentActivity").getString(0))
    }

    @Test fun validCandidateCannotReplaceAnAlreadyCorruptHistory() {
        val corrupt = """{"chats":{"lost":"""
        assertTrue(prefs.edit().putString("chats_v2", corrupt).commit())
        val candidate = JSONObject().put("chats", JSONObject().put("new-chat", JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "replacement")))))
        assertFalse(ChatHistoryStore.writeRoot(context, candidate))
        assertEquals(corrupt, prefs.getString("chats_v2", null))
    }

    @Test fun preferenceReadExceptionIsNotTreatedAsMissingHistory() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled preferences read failure")
            }
        }
        assertNull(ChatHistoryStore.readRoot(failingContext))
        assertFalse(ChatHistoryStore.writeRoot(failingContext, JSONObject().put("chats", JSONObject())))
    }

    @Test fun failedDurableCommitReturnsFalseAndKeepsPreviousHistory() {
        val original = JSONObject().put("currentId", "before")
            .put("chats", JSONObject().put("before", JSONObject().put("messages", JSONArray())))
        assertTrue(ChatHistoryStore.writeRoot(context, original))
        val failingPrefs = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getString" -> prefs.getString(args!![0] as String, args[1] as String?)
                "edit" -> {
                    lateinit var editorProxy: android.content.SharedPreferences.Editor
                    editorProxy = java.lang.reflect.Proxy.newProxyInstance(
                        android.content.SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java)
                    ) { _, editorMethod, _ ->
                        when (editorMethod.name) {
                            "putString", "remove", "clear" -> editorProxy
                            "commit" -> false
                            "apply" -> Unit
                            "toString" -> "FailingEditor"
                            else -> null
                        }
                    } as android.content.SharedPreferences.Editor
                    editorProxy
                }
                "toString" -> "FailingSharedPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as android.content.SharedPreferences
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                if (name == "chat_state") failingPrefs else super.getSharedPreferences(name, mode)
        }
        val replacement = JSONObject().put("currentId", "after")
            .put("chats", JSONObject().put("after", JSONObject().put("messages", JSONArray())))
        assertFalse(ChatHistoryStore.writeRoot(failingContext, replacement))
        // A failed durable commit quarantines reads even when the old snapshot remains visible.
        assertNull(ChatHistoryStore.readRoot(context))
        assertEquals("before", JSONObject(prefs.getString("chats_v2", null)!!).getString("currentId"))
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertEquals("before", ChatHistoryStore.readRoot(context)?.optString("currentId"))
    }

    @Test fun writeRejectsStructurallyInvalidCandidate() {
        val root = JSONObject().put("chats", JSONObject().put("chat-1", JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", 7).put("text", "bad")))))
        assertFalse(ChatHistoryStore.writeRoot(context, root))
        assertNull(prefs.getString("chats_v2", null))
    }

    @Test fun ambiguousHistoryCommitQuarantinesReadsAndFutureWrites() {
        val chatId = "ambiguous-history"
        val root = JSONObject().put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject().put("messages", JSONArray())))
        val failing = AmbiguousCommitContext(context, "chat_state")
        assertFalse(ChatHistoryStore.writeRoot(failing, root))
        assertNull(ChatHistoryStore.readRoot(context))
        assertFalse(ChatHistoryStore.writeRoot(context, JSONObject()))
        // The injected apply-before-failure path leaves the new in-memory snapshot visible.
        assertEquals(chatId, JSONObject(prefs.getString("chats_v2", null)!!).getString("currentId"))
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertEquals(chatId, ChatHistoryStore.readRoot(context)?.optString("currentId"))
    }
    @Test fun concurrentAtomicRootUpdatesPreserveBothActivityChanges() {
        val chatId = "concurrent-root-update"
        val root = JSONObject().put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject()
                .put("messages", JSONArray()).put("agentActivity", JSONArray())))
        assertTrue(ChatHistoryStore.writeRoot(context, root))

        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (1..2).map { index ->
            Thread {
                ready.countDown()
                try {
                    start.await()
                    assertTrue(ChatHistoryStore.updateRoot(context) { current ->
                        val chat = current.getJSONObject("chats").getJSONObject(chatId)
                        val activity = chat.optJSONArray("agentActivity") ?: JSONArray()
                        activity.put("worker-$index")
                        chat.put("agentActivity", activity)
                        true
                    })
                } catch (error: Throwable) {
                    failures += error
                }
            }.apply { start() }
        }
        assertTrue("Atomic update workers did not become ready", ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5_000L) }
        assertTrue("Atomic update workers did not finish", workers.none { it.isAlive })
        assertTrue("Atomic update failed: $failures", failures.isEmpty())

        val activity = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats")
            .getJSONObject(chatId).getJSONArray("agentActivity")
        assertEquals(2, activity.length())
        assertTrue((0 until activity.length()).map { activity.getString(it) }.containsAll(listOf("worker-1", "worker-2")))
    }

    @Test fun concurrentAtomicRootUpdatesPreserveMessageAndActivityChanges() {
        val chatId = "concurrent-message-activity"
        val original = JSONObject().put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject()
                .put("messages", JSONArray()).put("agentActivity", JSONArray())))
        assertTrue(ChatHistoryStore.writeRoot(context, original))

        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = listOf(
            Thread {
                ready.countDown()
                try {
                    start.await()
                    check(ChatHistoryStore.updateRoot(context) { root ->
                        root.getJSONObject("chats").getJSONObject(chatId)
                            .getJSONArray("messages")
                            .put(JSONObject().put("role", "assistant").put("text", "response"))
                        true
                    })
                } catch (error: Throwable) { failures += error }
            },
            Thread {
                ready.countDown()
                try {
                    start.await()
                    check(ChatHistoryStore.updateRoot(context) { root ->
                        root.getJSONObject("chats").getJSONObject(chatId)
                            .getJSONArray("agentActivity").put("agent-report")
                        true
                    })
                } catch (error: Throwable) { failures += error }
            }
        )
        workers.forEach { it.start() }
        assertTrue("Concurrent update workers did not become ready", ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5_000L) }
        assertTrue("Concurrent update workers did not finish", workers.none { it.isAlive })
        assertTrue("Concurrent update failed: $failures", failures.isEmpty())

        val chat = ChatHistoryStore.readRoot(context)!!.getJSONObject("chats").getJSONObject(chatId)
        assertEquals("response", chat.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals("agent-report", chat.getJSONArray("agentActivity").getString(0))
    }

    @Test fun updateCallbackFailureDoesNotPersistPartialMutationOrPoisonHistory() {
        val original = JSONObject().put("currentId", "stable")
            .put("chats", JSONObject().put("stable", JSONObject()
                .put("messages", JSONArray()).put("title", "Original")))
        assertTrue(ChatHistoryStore.writeRoot(context, original))

        assertFalse(ChatHistoryStore.updateRoot(context) { current ->
            current.getJSONObject("chats").getJSONObject("stable").put("title", "Partial")
            throw IllegalStateException("injected update callback failure")
        })

        assertEquals("Original", ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject("stable").getString("title"))
        assertFalse(SharedPreferencesWriteHealth.isUncertain("chat_state"))
        assertTrue(ChatHistoryStore.updateRoot(context) { current ->
            current.getJSONObject("chats").getJSONObject("stable").put("title", "Recovered")
            true
        })
        assertEquals("Recovered", ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject("stable").getString("title"))
    }

    @Test fun updateRootAmbiguousCommitQuarantinesAndRecoversAppliedSnapshotAfterRestart() {
        val chatId = "update-root-ambiguous"
        val original = JSONObject().put("currentId", chatId)
            .put("chats", JSONObject().put(chatId, JSONObject()
                .put("messages", JSONArray()).put("title", "Before")))
        assertTrue(ChatHistoryStore.writeRoot(context, original))

        val failing = AmbiguousCommitContext(context, "chat_state")
        assertFalse(ChatHistoryStore.updateRoot(failing) { current ->
            current.getJSONObject("chats").getJSONObject(chatId).put("title", "After")
            true
        })

        assertTrue(SharedPreferencesWriteHealth.isUncertain("chat_state"))
        assertNull(ChatHistoryStore.readRoot(context))
        assertEquals("After", JSONObject(prefs.getString("chats_v2", null)!!)
            .getJSONObject("chats").getJSONObject(chatId).getString("title"))

        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertEquals("After", ChatHistoryStore.readRoot(context)!!
            .getJSONObject("chats").getJSONObject(chatId).getString("title"))
    }


    @Test fun directWriteRootWaitsForSharedPreferenceLockThroughValidationAndCommit() {
        val ready = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val candidate = JSONObject().put("chats", JSONObject().put(
            "chat-direct", JSONObject().put("messages", JSONArray())
        ))
        val worker = Thread {
            ready.countDown()
            result.set(ChatHistoryStore.writeRoot(context, candidate))
            finished.countDown()
        }
        SharedPreferencesWriteHealth.withHealthyAccess("chat_state") {
            worker.start()
            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, worker.state)
            assertEquals(1L, finished.count)
        }
        worker.join(3_000L)
        assertEquals(0L, finished.count)
        assertTrue(result.get())
        assertTrue(ChatHistoryStore.readRoot(context)!!.getJSONObject("chats").has("chat-direct"))
    }


    @Test fun deletingCurrentChatAndSelectingReplacementCommitsTogether() {
        val oldId = "chat-to-delete"
        assertTrue(ChatHistoryStore.updateRootAndLastUserChatId(context, oldId) { root ->
            root.put("currentId", oldId)
            root.put("chats", JSONObject().put(oldId, JSONObject().put("messages", JSONArray())))
            true
        })

        assertTrue(ChatHistoryStore.updateRootAndLastUserChatId(context, "replacement-chat") { root ->
            val chats = root.getJSONObject("chats")
            assertNotNull(chats.remove(oldId))
            root.put("currentId", "replacement-chat")
            true
        })

        val root = ChatHistoryStore.readRoot(context)!!
        assertFalse(root.getJSONObject("chats").has(oldId))
        assertEquals("replacement-chat", root.getString("currentId"))
        assertEquals("replacement-chat", prefs.getString("last_user_chat_id", null))
    }

    @Test fun failedCurrentChatDeletionCommitKeepsChatAndNavigationMirror() {
        val oldId = "chat-to-delete"
        assertTrue(ChatHistoryStore.updateRootAndLastUserChatId(context, oldId) { root ->
            root.put("currentId", oldId)
            root.put("chats", JSONObject().put(oldId, JSONObject().put("messages", JSONArray())))
            true
        })

        val failing = AmbiguousCommitContext(context, "chat_state", applyBeforeFailure = false)
        assertFalse(ChatHistoryStore.updateRootAndLastUserChatId(failing, "replacement-chat") { root ->
            root.getJSONObject("chats").remove(oldId)
            root.put("currentId", "replacement-chat")
            true
        })

        assertTrue(SharedPreferencesWriteHealth.isUncertain("chat_state"))
        val root = JSONObject(prefs.getString("chats_v2", null)!!)
        assertTrue(root.getJSONObject("chats").has(oldId))
        assertEquals(oldId, root.getString("currentId"))
        assertEquals(oldId, prefs.getString("last_user_chat_id", null))
    }

    @Test fun currentChatAndLastUserNavigationMirrorCommitTogether() {
        assertTrue(ChatHistoryStore.updateRootAndLastUserChatId(context, "user-chat-atomic") { root ->
            root.put("currentId", "user-chat-atomic")
            if (!root.has("chats")) root.put("chats", JSONObject())
            true
        })
        assertEquals("user-chat-atomic", ChatHistoryStore.readRoot(context)!!.getString("currentId"))
        assertEquals("user-chat-atomic", prefs.getString("last_user_chat_id", null))
    }

    @Test fun failedCurrentChatNavigationCommitKeepsPreviousMirrorAndQuarantinesStore() {
        assertTrue(ChatHistoryStore.updateRootAndLastUserChatId(context, "previous-user-chat") { root ->
            root.put("currentId", "previous-user-chat")
            if (!root.has("chats")) root.put("chats", JSONObject())
            true
        })

        val failing = AmbiguousCommitContext(context, "chat_state", applyBeforeFailure = false)
        assertFalse(ChatHistoryStore.updateRootAndLastUserChatId(failing, "new-user-chat") { root ->
            root.put("currentId", "new-user-chat")
            true
        })

        assertTrue(SharedPreferencesWriteHealth.isUncertain("chat_state"))
        val storedRoot = JSONObject(prefs.getString("chats_v2", null)!!)
        assertEquals("previous-user-chat", storedRoot.getString("currentId"))
        assertEquals("previous-user-chat", prefs.getString("last_user_chat_id", null))
    }

    @Test fun ambiguousCurrentChatCommitNeverSplitsCanonicalIdFromNavigationMirror() {
        assertTrue(prefs.edit().putString("last_user_chat_id", "previous-user-chat").commit())
        val failing = AmbiguousCommitContext(context, "chat_state", applyBeforeFailure = true)
        assertFalse(ChatHistoryStore.updateRootAndLastUserChatId(failing, "new-user-chat") { root ->
            root.put("currentId", "new-user-chat")
            if (!root.has("chats")) root.put("chats", JSONObject())
            true
        })
        assertTrue(SharedPreferencesWriteHealth.isUncertain("chat_state"))
        val appliedRoot = JSONObject(prefs.getString("chats_v2", null)!!)
        assertEquals("new-user-chat", appliedRoot.getString("currentId"))
        assertEquals("new-user-chat", prefs.getString("last_user_chat_id", null))
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertEquals("new-user-chat", ChatHistoryStore.readRoot(context)!!.getString("currentId"))
    }

}
