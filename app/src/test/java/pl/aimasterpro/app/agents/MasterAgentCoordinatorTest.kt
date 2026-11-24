package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterAgentCoordinatorTest {
    private fun agent(id: String, vararg keywords: String) = AgentSpec(
        id = id,
        name = id,
        category = "Test",
        responsibility = "Test responsibility",
        keywords = keywords.toList()
    )

    @Test fun skillEvidenceRanksBeforeLoadAndUsesStableTieBreak() {
        val agents = listOf(
            agent("z-specialist", "android", "kotlin"),
            agent("a-specialist", "android", "kotlin"),
            agent("generalist", "android")
        )
        val plan = MasterAgentCoordinator.plan(
            objective = "Implement Android Kotlin feature",
            catalog = agents,
            enabledAgentIds = agents.map { it.id }.toSet(),
            providerReadyAgentIds = agents.map { it.id }.toSet(),
            loads = mapOf("a-specialist" to MasterAgentCoordinator.AgentLoad(activeTasks = 1, maxActiveTasks = 2))
        )
        assertEquals(listOf("z-specialist", "a-specialist", "generalist"),
            plan.selectedAgents.map { it.id })
        assertEquals("READY", plan.reason)
    }

    @Test fun disabledAndProviderUnavailableAgentsAreExcludedWithReasons() {
        val agents = listOf(agent("disabled", "database"), agent("offline", "database"))
        val plan = MasterAgentCoordinator.plan(
            objective = "Database migration",
            catalog = agents,
            enabledAgentIds = setOf("offline"),
            providerReadyAgentIds = emptySet()
        )
        assertTrue(plan.selectedAgents.isEmpty())
        assertEquals("NO_PROVIDER_READY_AGENT", plan.reason)
        assertEquals("AGENT_DISABLED", plan.rejectedAgentIds["disabled"])
        assertEquals("PROVIDER_NOT_READY", plan.rejectedAgentIds["offline"])
    }

    @Test fun saturatedAgentIsNotSelectedAndNextQualifiedAgentCanTakeWork() {
        val agents = listOf(agent("busy", "security"), agent("available", "security"))
        val plan = MasterAgentCoordinator.plan(
            objective = "Security review",
            catalog = agents,
            enabledAgentIds = agents.map { it.id }.toSet(),
            providerReadyAgentIds = agents.map { it.id }.toSet(),
            loads = mapOf("busy" to MasterAgentCoordinator.AgentLoad(activeTasks = 1, maxActiveTasks = 1)),
            maxAgents = 1
        )
        assertEquals(listOf("available"), plan.selectedAgents.map { it.id })
        assertEquals("AGENT_AT_CAPACITY", plan.rejectedAgentIds["busy"])
    }

    @Test fun noKeywordEvidenceDoesNotInventAnAgentMatch() {
        val agents = listOf(agent("only-agent", "database"))
        val plan = MasterAgentCoordinator.plan(
            objective = "Write a poem about autumn",
            catalog = agents,
            enabledAgentIds = setOf("only-agent"),
            providerReadyAgentIds = setOf("only-agent")
        )
        assertFalse(plan.isAssignable)
        assertEquals("NO_SKILL_MATCH", plan.reason)
    }

    @Test fun invalidWorkloadSnapshotFailsClosedForThatCandidate() {
        val candidate = agent("candidate", "android")
        val plan = MasterAgentCoordinator.plan(
            objective = "Android task",
            catalog = listOf(candidate),
            enabledAgentIds = setOf(candidate.id),
            providerReadyAgentIds = setOf(candidate.id),
            loads = mapOf(candidate.id to MasterAgentCoordinator.AgentLoad(activeTasks = -1))
        )
        assertTrue(plan.selectedAgents.isEmpty())
        assertEquals("INVALID_WORKLOAD_SNAPSHOT", plan.rejectedAgentIds[candidate.id])
    }

    @Test fun queueSnapshotLoadIsCountedAndFreeAgentIsSelected() {
        val agents = listOf(agent("busy-one", "android"), agent("busy-two", "android"), agent("free", "android"))
        val plan = MasterAgentCoordinator.planFromSnapshots(
            objective = "Implement Android Kotlin feature",
            catalog = agents,
            enabledAgentIds = agents.map { it.id }.toSet(),
            providerReadyAgentIds = agents.map { it.id }.toSet(),
            activeAssignments = listOf(listOf("busy-one"), listOf("busy-one", "busy-two")),
            maxAgents = 1,
            maxActiveTasksPerAgent = 1
        )
        assertEquals(listOf("free"), plan.selectedAgents.map { it.id })
        assertEquals("AGENT_AT_CAPACITY", plan.rejectedAgentIds["busy-one"])
        assertEquals("AGENT_AT_CAPACITY", plan.rejectedAgentIds["busy-two"])
    }

    @Test fun malformedQueueWorkloadSnapshotFailsClosed() {
        val candidate = agent("candidate", "android")
        val plan = MasterAgentCoordinator.planFromSnapshots(
            objective = "Android task",
            catalog = listOf(candidate),
            enabledAgentIds = setOf(candidate.id),
            providerReadyAgentIds = setOf(candidate.id),
            activeAssignments = listOf(listOf("")),
            maxAgents = 1
        )
        assertFalse(plan.isAssignable)
        assertEquals("INVALID_WORKLOAD_SNAPSHOT", plan.reason)
    }

    @Test fun preselectedExplicitTeamMemberSurvivesMissingKeywordOverlap() {
        val helper = agent("requirements-helper", "requirements")
        val plan = MasterAgentCoordinator.planFromSnapshots(
            objective = "uruchom cały zespół",
            catalog = listOf(helper),
            enabledAgentIds = setOf(helper.id),
            providerReadyAgentIds = setOf(helper.id),
            activeAssignments = emptyList(),
            maxAgents = 1,
            includeUnmatchedCandidates = true
        )
        assertEquals(listOf(helper.id), plan.selectedAgents.map { it.id })
        assertEquals("READY", plan.reason)
    }
}
