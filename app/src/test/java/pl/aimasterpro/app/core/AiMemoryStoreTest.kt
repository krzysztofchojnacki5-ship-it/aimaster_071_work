package pl.aimasterpro.app.core

import android.content.Context
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

    @Test fun operationalSummaryIsOptIn() {
        val defaultContext = AiMemoryStore.contextString(context, "", maxChars = 2_000)
        assertFalse(defaultContext.contains("METRYKI OPERACYJNE"))
        val expanded = AiMemoryStore.contextString(context, "", maxChars = 2_000, includeOperationalSummary = true)
        assertTrue(expanded.length <= 2_000)
    }
}
