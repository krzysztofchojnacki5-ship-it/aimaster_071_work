package pl.aimasterpro.app.core

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LearningJournalStoreTest {
    private lateinit var context: Context

    private var previousMemories: String? = null

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        previousMemories = context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE).getString("memories", null)
        LearningJournalStore.clear(context)
    }

    @After fun tearDown() {
        LearningJournalStore.clear(context)
        context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE).edit().apply {
            if (previousMemories == null) remove("memories") else putString("memories", previousMemories)
        }.apply()
    }

    @Test fun recordsPersistAndAggregateOperationalAgentStats() {
        LearningJournalStore.record(context, LearningJournalStore.Event(100L, "agent", "reviewer", "groq", "model-a", "COMPLETED", 2_000L, 1, "", "task-abc", 123L, 456L))
        LearningJournalStore.record(context, LearningJournalStore.Event(200L, "agent", "reviewer", "groq", "model-a", "FAILED", 4_000L, 2, "timeout"))
        val loaded = LearningJournalStore.load(context)
        assertEquals(2, loaded.size)
        assertEquals("task-abc", loaded.last().taskId)
        assertEquals(123L, loaded.last().inputTokens)
        assertEquals(456L, loaded.last().outputTokens)
        val summary = LearningJournalStore.contextString(context)
        assertTrue(summary.contains("reviewer"))
        assertTrue(summary.contains("50%"))
        assertTrue(summary.contains("3s"))
    }

    @Test fun clearRemovesJournalWithoutTouchingApprovedMemory() {
        AiMemoryStore.addMemory(context, "TEST_APPROVED_MEMORY")
        LearningJournalStore.record(context, LearningJournalStore.Event(1L, "chat", "MASTER", "groq", "model-a", "COMPLETED", 10L, 1, ""))
        LearningJournalStore.clear(context)
        assertTrue(LearningJournalStore.load(context).isEmpty())
        assertTrue(AiMemoryStore.getMemories(context).contains("TEST_APPROVED_MEMORY"))
    }
}
