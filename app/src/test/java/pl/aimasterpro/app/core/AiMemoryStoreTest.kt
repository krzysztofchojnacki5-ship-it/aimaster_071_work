package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AiMemoryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("ai_master_memory_store_v1")
    }

    @Test fun contextIsBoundedAndDoesNotInjectOperationalStatsByDefault() {
        repeat(30) { AiMemoryStore.addMemory(context, "Reguła $it dotycząca Kotlin Android architektury i testów.") }
        val text = AiMemoryStore.contextString(context, "Kotlin Android testy", maxChars = 900)
        assertTrue(text.length <= 900)
        assertFalse(text.contains("HISTORIA WYKONANIA AGENTÓW"))
        assertTrue(text.contains("Kotlin") || text.contains("Oddzielaj potwierdzone fakty"))
    }

    @Test fun relevantMemoriesAreRankedAheadOfUnrelatedRecentRules() {
        AiMemoryStore.addMemory(context, "Zasada dotycząca fotografii i obróbki zdjęć.")
        AiMemoryStore.addMemory(context, "Przy Kotlinie uruchamiaj testy jednostkowe i sprawdzaj null safety.")
        val text = AiMemoryStore.contextString(context, "Popraw kod Kotlin i testy", maxChars = 2_000)
        assertTrue(text.indexOf("Kotlinie") < text.indexOf("fotografii") || !text.contains("fotografii"))
    }

    @Test fun malformedMemoryJournalIsNotOverwrittenByAddOrRemove() {
        val prefs = context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
        val corrupt = """["valid memory",{"unexpected":"object"}]"""
        assertTrue(prefs.edit().putString("memories", corrupt).commit())
        AiMemoryStore.addMemory(context, "Nowa zatwierdzona zasada pamięci.")
        AiMemoryStore.removeMemory(context, "valid memory")
        assertEquals(corrupt, prefs.getString("memories", null))
    }

    @Test fun validMemoryMutationIsCommittedAndReadable() {
        val prefs = context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
        assertTrue(AiMemoryStore.addMemory(context, "Zatwierdzona pamięć do testu trwałego zapisu."))
        assertTrue(prefs.getString("memories", null).orEmpty().contains("trwałego zapisu"))
        assertTrue(AiMemoryStore.getMemories(context).contains("Zatwierdzona pamięć do testu trwałego zapisu."))
    }

    @Test fun preferenceAccessFailureDoesNotCrashOrPretendMemoryWasSaved() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled memory preferences failure")
            }
        }
        assertFalse(AiMemoryStore.addMemory(failingContext, "Nowa zasada nie może zostać utracona."))
        assertFalse(AiMemoryStore.removeMemory(failingContext, "nieistniejąca zasada"))
        assertTrue(AiMemoryStore.getMemories(failingContext).isNotEmpty())
    }

    @Test fun operationalSummaryIsOptIn() {
        val defaultContext = AiMemoryStore.contextString(context, "", maxChars = 2_000)
        assertFalse(defaultContext.contains("METRYKI OPERACYJNE"))
        val expanded = AiMemoryStore.contextString(context, "", maxChars = 2_000, includeOperationalSummary = true)
        assertTrue(expanded.length <= 2_000)
    }

    @Test fun ambiguousMemoryCommitBlocksFurtherMutation() {
        val failing = AmbiguousCommitContext(context, "ai_master_memory_store_v1")
        assertFalse(AiMemoryStore.addMemory(failing, "Ambiguous write must not be trusted."))
        assertFalse(AiMemoryStore.addMemory(context, "Second write must be blocked."))
        assertFalse(AiMemoryStore.removeMemory(context, "anything"))
        assertTrue(AiMemoryStore.getMemories(context).isNotEmpty())
    }
}
