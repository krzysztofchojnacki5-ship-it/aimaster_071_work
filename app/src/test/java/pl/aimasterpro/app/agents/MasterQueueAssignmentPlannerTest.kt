package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.aimasterpro.app.core.MasterTaskQueueStore

class MasterQueueAssignmentPlannerTest {
    private fun agent(id: String) = AgentSpec(
        id = id,
        name = id,
        category = "Inżynieria",
        responsibility = "Implementuje funkcje Android.",
        keywords = listOf("android", "kotlin")
    )

    private fun task(
        id: String,
        status: MasterTaskQueueStore.Status,
        assigned: List<String> = emptyList(),
        updatedAt: Long = 100L,
        runId: Int = 0
    ) = MasterTaskQueueStore.Task(
        id = id,
        title = "Zadanie $id",
        objective = "Implement Android Kotlin module and verify durable queue behavior.",
        assignedAgentIds = assigned,
        dependencies = emptyList(),
        priority = 2,
        status = status,
        currentStep = "",
        createdAt = 10L,
        updatedAt = updatedAt,
        runId = runId
    )

    @Test fun planUsesExactQueuedSnapshotAndSkipsBusyAgent() {
        val busy = agent("busy-agent")
        val available = agent("available-agent")
        val queued = task("queue-target", MasterTaskQueueStore.Status.QUEUED, updatedAt = 123L, runId = 4)
        val running = task("queue-running", MasterTaskQueueStore.Status.RUNNING, listOf(busy.id), 122L, 2)
        val queue = MasterTaskQueueStore.Snapshot(
            MasterTaskQueueStore.ReadStatus.AVAILABLE,
            tasks = listOf(queued, running)
        )

        val decision = MasterQueueAssignmentPlanner.plan(
            queue = queue,
            taskId = queued.id,
            enabledCatalog = listOf(busy, available),
            providerReadyAgentIds = setOf(busy.id, available.id),
            maxAgents = 1
        )

        assertTrue(decision.isAssignable)
        assertEquals(listOf(available.id), decision.selectedAgentIds)
        assertEquals(123L, decision.expectedUpdatedAt)
        assertEquals(4, decision.expectedRunId)
    }

    @Test fun providerUnavailableCandidateIsNotProposed() {
        val ready = agent("ready-agent")
        val offline = agent("offline-agent")
        val queued = task("queue-provider", MasterTaskQueueStore.Status.QUEUED)
        val decision = MasterQueueAssignmentPlanner.plan(
            queue = MasterTaskQueueStore.Snapshot(
                MasterTaskQueueStore.ReadStatus.AVAILABLE,
                tasks = listOf(queued)
            ),
            taskId = queued.id,
            enabledCatalog = listOf(ready, offline),
            providerReadyAgentIds = setOf(ready.id),
            maxAgents = 2
        )
        assertEquals(listOf(ready.id), decision.selectedAgentIds)
        assertEquals("PROVIDER_NOT_READY", decision.selection.rejectedAgentIds[offline.id])
    }

    @Test fun unavailableQueueAndNonAssignableTaskFailClosed() {
        val candidate = agent("candidate-agent")
        val queued = task("queue-closed", MasterTaskQueueStore.Status.QUEUED)
        val unavailable = MasterQueueAssignmentPlanner.plan(
            queue = MasterTaskQueueStore.Snapshot(MasterTaskQueueStore.ReadStatus.UNAVAILABLE),
            taskId = queued.id,
            enabledCatalog = listOf(candidate),
            providerReadyAgentIds = setOf(candidate.id)
        )
        assertFalse(unavailable.isAssignable)
        assertEquals("QUEUE_UNAVAILABLE", unavailable.rejectionReason)

        val running = task("queue-running-target", MasterTaskQueueStore.Status.RUNNING, listOf(candidate.id))
        val notAssignable = MasterQueueAssignmentPlanner.plan(
            queue = MasterTaskQueueStore.Snapshot(
                MasterTaskQueueStore.ReadStatus.AVAILABLE,
                tasks = listOf(running)
            ),
            taskId = running.id,
            enabledCatalog = listOf(candidate),
            providerReadyAgentIds = setOf(candidate.id)
        )
        assertFalse(notAssignable.isAssignable)
        assertEquals("TASK_NOT_ASSIGNABLE", notAssignable.rejectionReason)
    }

    @Test fun pausedFirstRunCanBeReplannedToAReadySubstitute() {
        val unavailable = agent("unavailable-specialist")
        val substitute = agent("ready-substitute")
        val paused = task(
            "queue-replacement",
            MasterTaskQueueStore.Status.PAUSED,
            assigned = listOf(unavailable.id),
            updatedAt = 222L,
            runId = 0
        )
        val decision = MasterQueueAssignmentPlanner.plan(
            queue = MasterTaskQueueStore.Snapshot(
                MasterTaskQueueStore.ReadStatus.AVAILABLE,
                tasks = listOf(paused)
            ),
            taskId = paused.id,
            enabledCatalog = listOf(unavailable, substitute),
            providerReadyAgentIds = setOf(substitute.id),
            maxAgents = 1
        )
        assertTrue(decision.isAssignable)
        assertEquals(listOf(substitute.id), decision.selectedAgentIds)
        assertEquals(222L, decision.expectedUpdatedAt)
        assertEquals(0, decision.expectedRunId)
    }
}
