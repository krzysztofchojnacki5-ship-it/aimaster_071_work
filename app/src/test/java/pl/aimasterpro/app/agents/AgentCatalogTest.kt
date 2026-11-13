package pl.aimasterpro.app.agents

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.aimasterpro.app.core.WorkMode

@RunWith(RobolectricTestRunner::class)
class AgentCatalogTest {
    @Test
    fun testAllAgentsLoaded() {
        assertTrue(AgentCatalog.all.isNotEmpty())
        assertEquals(67, AgentCatalog.all.size)
        AgentCatalog.all.forEach { spec ->
            assertFalse(spec.id.isBlank())
            assertFalse(spec.name.isBlank())
            assertFalse(spec.systemPrompt.isBlank())
        }
    }

    @Test
    fun testAgentRoutingKeywords() {
        val crashPrompt = "Mam nagły crash i błąd krytyczny w aplikacji."
        val selected = AgentCatalog.selectForPrompt(crashPrompt, maxAgents = 2)
        assertTrue(selected.isNotEmpty())
        val matchedIds = selected.map { it.id }
        assertTrue(matchedIds.any { it.contains("diag") || it.contains("debug") || it.contains("error") || it.contains("crash") || it.contains("diagnostics") })
    }


    @Test
    fun noKeywordMatchDoesNotWakeDefaultAgents() {
        assertTrue(AgentCatalog.selectForPrompt("Dzień dobry, co słychać?").isEmpty())
    }

    @Test
    fun testWorkModeStoredParsing() {
        assertEquals(WorkMode.PLANNING, WorkMode.fromStored("PLANNING"))
        assertEquals(WorkMode.DIAGNOSTICS, WorkMode.fromStored("Diagnostyka"))
        assertEquals(WorkMode.AUTOMATION, WorkMode.fromStored("unknown_mode"))
    }
}
