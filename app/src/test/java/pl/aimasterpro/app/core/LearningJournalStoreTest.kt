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
        SharedPreferencesWriteHealth.resetForTests("ai_learning_journal_v1")
        context = RuntimeEnvironment.getApplication()
        previousMemories = context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE).getString("memories", null)
        LearningJournalStore.clear(context)
    }

    @After fun tearDown() {
        SharedPreferencesWriteHealth.resetForTests("ai_learning_journal_v1")
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

    @Test fun legacyJournalRowsWithoutTokenTotalsRemainReadableAndAppendable() {
        val prefs = context.getSharedPreferences("ai_learning_journal_v1", Context.MODE_PRIVATE)
        val legacy = """[{"ts":10,"kind":"agent","agent":"coder","provider":"groq","model":"legacy-model","status":"COMPLETED","duration":500,"attempts":1,"category":""}]"""
        assertTrue(prefs.edit().putString("events", legacy).commit())
        assertEquals(0L, LearningJournalStore.load(context).single().inputTokens)
        LearningJournalStore.record(
            context, LearningJournalStore.Event(20L, "agent", "tester", "groq", "new-model", "COMPLETED", 100L, 1, "")
        )
        assertEquals(2, LearningJournalStore.load(context).size)
        assertTrue(LearningJournalStore.load(context).any { it.model == "legacy-model" })
    }

    @Test fun malformedOperationalJournalIsPreservedWhenNewEventIsRecorded() {
        val prefs = context.getSharedPreferences("ai_learning_journal_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"kind":"agent","agent":"coder"},null]"""
        assertTrue(prefs.edit().putString("events", corrupt).commit())
        LearningJournalStore.record(
            context, LearningJournalStore.Event(300L, "agent", "tester", "groq", "model", "COMPLETED", 100L, 1, "")
        )
        assertEquals(corrupt, prefs.getString("events", null))
        assertTrue(LearningJournalStore.load(context).isEmpty())
    }

    @Test fun fractionalAndNegativeCountersInvalidateWholeJournal() {
        val prefs = context.getSharedPreferences("ai_learning_journal_v1", Context.MODE_PRIVATE)
        val fractional = """[{"ts":1.5,"kind":"agent","agent":"coder","provider":"groq","model":"m","status":"COMPLETED","duration":10,"attempts":1,"category":""}]"""
        assertTrue(prefs.edit().putString("events", fractional).commit())
        assertTrue(LearningJournalStore.load(context).isEmpty())
        LearningJournalStore.record(context, LearningJournalStore.Event(20L, "agent", "tester", "groq", "m", "COMPLETED", 10L, 1, ""))
        assertEquals(fractional, prefs.getString("events", null))
        val negative = """[{"ts":1,"kind":"agent","agent":"coder","provider":"groq","model":"m","status":"COMPLETED","duration":-1,"attempts":1,"category":""}]"""
        assertTrue(prefs.edit().putString("events", negative).commit())
        assertTrue(LearningJournalStore.load(context).isEmpty())
        assertEquals(negative, prefs.getString("events", null))
    }

    @Test fun invalidNewEventIsRejectedInsteadOfClampedToZero() {
        val prefs = context.getSharedPreferences("ai_learning_journal_v1", Context.MODE_PRIVATE)
        val valid = """[{"ts":1,"kind":"agent","agent":"coder","provider":"groq","model":"m","status":"COMPLETED","duration":10,"attempts":1,"category":""}]"""
        assertTrue(prefs.edit().putString("events", valid).commit())
        LearningJournalStore.record(context, LearningJournalStore.Event(2L, "agent", "tester", "groq", "m", "FAILED", -1L, 1, ""))
        assertEquals(valid, prefs.getString("events", null))
    }

    @Test fun preferenceAccessFailureDoesNotCrashOrReportSuccessfulClear() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled learning journal preferences failure")
            }
        }
        LearningJournalStore.record(
            failingContext,
            LearningJournalStore.Event(10L, "agent", "tester", "groq", "model", "COMPLETED", 100L, 1, "")
        )
        assertTrue(LearningJournalStore.load(failingContext).isEmpty())
        assertTrue(!LearningJournalStore.clear(failingContext))
    }

    @Test fun clearRemovesJournalWithoutTouchingApprovedMemory() {
        AiMemoryStore.addMemory(context, "TEST_APPROVED_MEMORY")
        LearningJournalStore.record(context, LearningJournalStore.Event(1L, "chat", "MASTER", "groq", "model-a", "COMPLETED", 10L, 1, ""))
        LearningJournalStore.clear(context)
        assertTrue(LearningJournalStore.load(context).isEmpty())
        assertTrue(AiMemoryStore.getMemories(context).contains("TEST_APPROVED_MEMORY"))
    }
    @Test fun ambiguousCommitQuarantinesJournalReadsWritesAndClear() {
        val prefs = context.getSharedPreferences("ai_learning_journal_v1", Context.MODE_PRIVATE)
        val original = "[]"
        assertTrue(prefs.edit().putString("events", original).commit())
        val failingEditor = object : android.content.SharedPreferences.Editor by prefs.edit().putString("events", "[ambiguous]") {
            override fun commit(): Boolean = false
        }
        assertTrue(!SharedPreferencesWriteHealth.commit("ai_learning_journal_v1", failingEditor))

        LearningJournalStore.record(context, LearningJournalStore.Event(10L, "agent", "tester", "p", "m", "COMPLETED", 10L, 1, ""))
        assertTrue(LearningJournalStore.load(context).isEmpty())
        assertTrue(!LearningJournalStore.clear(context))
        assertEquals(original, prefs.getString("events", null))
    }
}
