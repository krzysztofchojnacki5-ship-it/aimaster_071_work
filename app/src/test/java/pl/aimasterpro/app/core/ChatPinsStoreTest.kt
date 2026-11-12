package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatPinsStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("chat_state", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("chat_state")
    }

    @Test fun toggleAddsAndRemovesPinWithDurableCommits() {
        assertTrue(ChatPinsStore.toggle(context, "chat-1"))
        assertEquals(setOf("chat-1"), ChatPinsStore.read(context))
        assertTrue(ChatPinsStore.toggle(context, "chat-1"))
        assertEquals(emptySet<String>(), ChatPinsStore.read(context))
    }

    @Test fun removeIsIdempotentAndPreservesOtherPins() {
        assertTrue(prefs.edit().putStringSet("pinned_chats", setOf("chat-1", "chat-2")).commit())
        assertTrue(ChatPinsStore.remove(context, "chat-1"))
        assertEquals(setOf("chat-2"), ChatPinsStore.read(context))
        assertTrue(ChatPinsStore.remove(context, "missing"))
        assertEquals(setOf("chat-2"), ChatPinsStore.read(context))
    }

    @Test fun failedCommitQuarantinesPinStoreAndDoesNotReportSuccess() {
        val failing = AmbiguousCommitContext(context, "chat_state", applyBeforeFailure = false)
        assertFalse(ChatPinsStore.toggle(failing, "chat-1"))
        assertTrue(SharedPreferencesWriteHealth.isUncertain("chat_state"))
        assertNull(ChatPinsStore.read(context))
        assertEquals(emptySet<String>(), prefs.getStringSet("pinned_chats", emptySet()))
    }

    @Test fun pinReadFailsClosedAfterStorageQuarantine() {
        assertTrue(ChatPinsStore.toggle(context, "chat-1"))
        SharedPreferencesWriteHealth.resetForTests("chat_state")
        assertFalse(ChatPinsStore.toggle(AmbiguousCommitContext(context, "chat_state"), "chat-2"))
        assertNull(ChatPinsStore.read(context))
    }
    @Test fun toggleRejectsOversizedIdsAndCapacityWithoutDroppingExistingPins() {
        assertFalse(ChatPinsStore.toggle(context, "x".repeat(201)))
        val pins = (1..100).map { "chat-$it" }.toSet()
        assertTrue(prefs.edit().putStringSet("pinned_chats", pins).commit())
        assertFalse(ChatPinsStore.toggle(context, "chat-new"))
        assertEquals(pins, ChatPinsStore.read(context))
        assertTrue(ChatPinsStore.toggle(context, "chat-1"))
        assertEquals(99, ChatPinsStore.read(context)?.size)
    }

    @Test fun malformedPersistedPinSetsFailClosedAndRemainUnchanged() {
        val oversizedId = "x".repeat(201)
        assertTrue(prefs.edit().putStringSet("pinned_chats", setOf(oversizedId)).commit())
        assertNull(ChatPinsStore.read(context))
        assertFalse(ChatPinsStore.toggle(context, "another-chat"))
        assertEquals(setOf(oversizedId), prefs.getStringSet("pinned_chats", emptySet()))
        val tooMany = (1..101).map { "chat-$it" }.toSet()
        assertTrue(prefs.edit().putStringSet("pinned_chats", tooMany).commit())
        assertNull(ChatPinsStore.read(context))
        assertFalse(ChatPinsStore.remove(context, "chat-1"))
        assertEquals(tooMany, prefs.getStringSet("pinned_chats", emptySet()))
    }
}
