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

}
