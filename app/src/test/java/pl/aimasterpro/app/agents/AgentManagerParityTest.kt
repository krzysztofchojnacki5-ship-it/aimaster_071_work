package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentManagerParityTest {

    @Test
    fun testAgentCatalogNotEmpty() {
        assertTrue(AgentCatalog.all.isNotEmpty())
        val lead = AgentCatalog.find("lead-coordinator")
        assertNotNull(lead)
        assertEquals("Lead Coordinator", lead?.name)
    }

    @Test
    fun testAgentManagerStoreSummary() {
        val store = AgentManagerStore(null!!)
        val summary = store.getSummary()
        assertTrue(summary.totalAgents > 0)
        assertTrue(summary.activeExecutions.isNotEmpty())
        val actionResult = store.controlAgent("exec-test-1", "stop")
        assertTrue(actionResult)
    }
}
