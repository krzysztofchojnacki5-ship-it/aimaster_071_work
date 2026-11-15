package pl.aimasterpro.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConnectionTestGateTest {
    @Test
    fun newerTestForSameRoleMakesPreviousRequestStale() {
        val gate = AgentConnectionTestGate()
        val first = gate.begin("RESEARCHER", "provider-a")
        val second = gate.begin("RESEARCHER", "provider-a")

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun testsForDifferentRolesRemainIndependent() {
        val gate = AgentConnectionTestGate()
        val researcher = gate.begin("RESEARCHER", "provider-a")
        val coder = gate.begin("CODER", "provider-b")

        assertTrue(gate.isCurrent(researcher))
        assertTrue(gate.isCurrent(coder))
    }

    @Test
    fun assignmentChangeInvalidationRejectsLateResult() {
        val gate = AgentConnectionTestGate()
        val request = gate.begin("RESEARCHER", "provider-a")

        gate.invalidate("RESEARCHER")

        assertFalse(gate.isCurrent(request))
        assertFalse(gate.complete(request))
    }

    @Test
    fun completionIsAcceptedOnlyOnce() {
        val gate = AgentConnectionTestGate()
        val request = gate.begin("RESEARCHER", "provider-a")

        assertTrue(gate.complete(request))
        assertFalse(gate.isCurrent(request))
        assertFalse(gate.complete(request))
    }

    @Test
    fun lateCallbackFromCompletedRequestCannotCompleteNewSameProviderTest() {
        val gate = AgentConnectionTestGate()
        val first = gate.begin("RESEARCHER", "provider-a")

        assertTrue(gate.complete(first))

        val second = gate.begin("RESEARCHER", "provider-a")

        assertFalse(gate.isCurrent(first))
        assertFalse(gate.complete(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun invalidateAllRejectsEveryPendingAgentTest() {
        val gate = AgentConnectionTestGate()
        val researcher = gate.begin("RESEARCHER", "provider-a")
        val coder = gate.begin("CODER", "provider-b")

        gate.invalidateAll()

        assertFalse(gate.isCurrent(researcher))
        assertFalse(gate.isCurrent(coder))
    }
}
