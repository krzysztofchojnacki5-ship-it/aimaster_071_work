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

    private fun mutateAdd(ctx: Context, memory: String): Boolean {
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER,
            MemoryGovernance.Mutation.ADD_SHARED_RULE, "test-add:${java.util.UUID.randomUUID()}",
            "Test confirms shared-memory addition", "test-approval")
        return MemoryMutationGate.execute(ctx, request) { permit -> AiMemoryStore.addMemory(ctx, memory, permit) }
    }
    private fun mutateRemove(ctx: Context, memory: String): Boolean {
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER,
            MemoryGovernance.Mutation.REMOVE_SHARED_RULE, "test-remove:${java.util.UUID.randomUUID()}",
            "Test confirms shared-memory removal", "test-approval")
        return MemoryMutationGate.execute(ctx, request) { permit -> AiMemoryStore.removeMemory(ctx, memory, permit) }
    }

    @Before fun reset() {
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
        context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("ai_master_memory_store_v1")
    }

    @Test fun contextIsBoundedAndDoesNotInjectOperationalStatsByDefault() {
        repeat(30) { mutateAdd(context, "Reguła $it dotycząca Kotlin Android architektury i testów.") }
        val text = AiMemoryStore.contextString(context, "Kotlin Android testy", maxChars = 900)
        assertTrue(text.length <= 900)
        assertFalse(text.contains("HISTORIA WYKONANIA AGENTÓW"))
        assertTrue(text.contains("Kotlin") || text.contains("Oddzielaj potwierdzone fakty"))
    }

    @Test fun relevantMemoriesAreRankedAheadOfUnrelatedRecentRules() {
        mutateAdd(context, "Zasada dotycząca fotografii i obróbki zdjęć.")
        mutateAdd(context, "Przy Kotlinie uruchamiaj testy jednostkowe i sprawdzaj null safety.")
        val text = AiMemoryStore.contextString(context, "Popraw kod Kotlin i testy", maxChars = 2_000)
        assertTrue(text.indexOf("Kotlinie") < text.indexOf("fotografii") || !text.contains("fotografii"))
    }

    @Test fun malformedMemoryJournalIsNotOverwrittenByAddOrRemove() {
        val prefs = context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
        val corrupt = """["valid memory",{"unexpected":"object"}]"""
        assertTrue(prefs.edit().putString("memories", corrupt).commit())
        mutateAdd(context, "Nowa zatwierdzona zasada pamięci.")
        mutateRemove(context, "valid memory")
        assertEquals(corrupt, prefs.getString("memories", null))
    }

    @Test fun validMemoryMutationIsCommittedAndReadable() {
        val prefs = context.getSharedPreferences("ai_master_memory_store_v1", Context.MODE_PRIVATE)
        assertTrue(mutateAdd(context, "Zatwierdzona pamięć do testu trwałego zapisu."))
        assertTrue(prefs.getString("memories", null).orEmpty().contains("trwałego zapisu"))
        assertTrue(AiMemoryStore.getMemories(context).contains("Zatwierdzona pamięć do testu trwałego zapisu."))
    }

    @Test fun preferenceAccessFailureDoesNotCrashOrPretendMemoryWasSaved() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled memory preferences failure")
            }
        }
        assertFalse(mutateAdd(failingContext, "Nowa zasada nie może zostać utracona."))
        assertFalse(mutateRemove(failingContext, "nieistniejąca zasada"))
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
        assertFalse(mutateAdd(failing, "Ambiguous write must not be trusted."))
        assertFalse(mutateAdd(context, "Second write must be blocked."))
        assertFalse(mutateRemove(context, "anything"))
        assertTrue(AiMemoryStore.getMemories(context).isNotEmpty())
    }
}
