package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class MasterOperationsOverviewTest {
    private fun queue(
        status: MasterTaskQueueStore.ReadStatus = MasterTaskQueueStore.ReadStatus.AVAILABLE,
        tasks: List<MasterTaskQueueStore.Task> = emptyList(),
        events: List<MasterTaskQueueStore.Event> = emptyList()
    ) = MasterTaskQueueStore.Snapshot(status, tasks, events)

    private fun handoffs(
        status: AgentHandoffStore.ReadStatus = AgentHandoffStore.ReadStatus.AVAILABLE,
        messages: List<AgentHandoffStore.Handoff> = emptyList()
    ) = AgentHandoffStore.Snapshot(status, messages)

    private fun task(
        id: String,
        status: MasterTaskQueueStore.Status,
        updatedAt: Long = 10L
    ) = MasterTaskQueueStore.Task(
        id = id, title = "Zadanie $id", objective = "Cel", assignedAgentIds = listOf("reviewer"),
        dependencies = emptyList(), priority = 1, status = status, currentStep = "Analiza",
        createdAt = 1L, updatedAt = updatedAt, runId = 3
    )

    private fun event(
        id: String,
        status: String,
        timestamp: Long,
        taskId: String = "task-overview-1",
        runId: Int = 3,
        actor: String = "agent:reviewer",
        operation: String = "AGENT_PROGRESS"
    ) = MasterTaskQueueStore.Event(
        id = id, taskId = taskId, actor = actor, operation = operation,
        fromStatus = "", toStatus = status, detail = "Tego tekstu nie pokazujemy.",
        timestamp = timestamp, providerId = "provider", model = "model", runId = runId
    )

    private fun handoff(id: String, state: AgentHandoffStore.State) = AgentHandoffStore.Handoff(
        id = id, taskId = "task-overview-1", runId = 3,
        senderAgentId = "reviewer", recipientAgentId = "tester",
        payload = "Prywatny payload przekazania", state = state,
        createdAt = 1L, updatedAt = 20L
    )

    @Test fun aggregatesTaskStatesAndOnlyActiveOrBlockedTaskDetails() {
        val result = MasterOperationsOverview.build(
            queue(tasks = listOf(
                task("task-queued", MasterTaskQueueStore.Status.QUEUED),
                task("task-running", MasterTaskQueueStore.Status.RUNNING, 30L),
                task("task-blocked", MasterTaskQueueStore.Status.BLOCKED, 20L),
                task("task-done", MasterTaskQueueStore.Status.COMPLETED)
            )),
            handoffs()
        )
        assertEquals(4, result.totalTasks)
        assertEquals(1, result.taskCounts[MasterTaskQueueStore.Status.RUNNING])
        assertEquals(1, result.taskCounts[MasterTaskQueueStore.Status.BLOCKED])
        assertEquals(listOf("task-running", "task-blocked"), result.activeTasks.map { it.id })
    }

    @Test fun unavailableQueueIsNotReportedAsAnEmptyQueue() {
        val result = MasterOperationsOverview.build(
            queue(status = MasterTaskQueueStore.ReadStatus.CORRUPT),
            handoffs()
        )
        assertNull(result.totalTasks)
        assertTrue(result.taskCounts.isEmpty())
        assertTrue(result.activeTasks.isEmpty())
        assertFalse(result.isComplete)
        assertEquals(MasterTaskQueueStore.ReadStatus.CORRUPT, result.queueStatus)
    }

    @Test fun unavailableHandoffStoreDoesNotProduceZeroCounts() {
        val result = MasterOperationsOverview.build(
            queue(tasks = listOf(task("task-running", MasterTaskQueueStore.Status.RUNNING))),
            handoffs(status = AgentHandoffStore.ReadStatus.UNAVAILABLE)
        )
        assertNull(result.pendingHandoffs)
        assertNull(result.claimedHandoffs)
        assertNull(result.rejectedHandoffs)
        assertFalse(result.isComplete)
    }

    @Test fun handoffCountsUseDurableStates() {
        val result = MasterOperationsOverview.build(
            queue(),
            handoffs(messages = listOf(
                handoff("handoff-pending", AgentHandoffStore.State.PENDING),
                handoff("handoff-claimed", AgentHandoffStore.State.CLAIMED),
                handoff("handoff-rejected", AgentHandoffStore.State.REJECTED),
                handoff("handoff-ack", AgentHandoffStore.State.ACKNOWLEDGED)
            ))
        )
        assertEquals(1, result.pendingHandoffs)
        assertEquals(1, result.claimedHandoffs)
        assertEquals(1, result.rejectedHandoffs)
    }

    @Test fun latestAgentEventIsScopedByTaskAndRun() {
        val result = MasterOperationsOverview.build(
            queue(events = listOf(
                event("old", "REQUESTING", 10L),
                event("new", "COMPLETED", 20L),
                event("old-run", "FAILED", 99L, runId = 2),
                event("other-task", "FAILED", 100L, taskId = "task-other")
            )),
            handoffs()
        )
        assertEquals(3, result.recentAgents.size)
        val current = result.recentAgents.single { it.taskId == "task-overview-1" && it.runId == 3 }
        assertEquals(3, current.runId)
        assertEquals("COMPLETED", current.status)
        assertEquals("provider / model", current.providerLabel)
    }

    @Test fun freeFormEventDetailsAndHandoffPayloadsAreNeverProjected() {
        val result = MasterOperationsOverview.build(
            queue(events = listOf(event("event", "RUNNING", 10L))),
            handoffs(messages = listOf(handoff("handoff", AgentHandoffStore.State.PENDING)))
        )
        val rendered = result.toString()
        assertFalse(rendered.contains("Tego tekstu nie pokazujemy"))
        assertFalse(rendered.contains("Prywatny payload przekazania"))
    }

    @Test fun limitsAreClampedAndNewestAgentActivityComesFirst() {
        val events = (1..25).map { index ->
            event("event-$index", "STATUS_$index", index.toLong(), taskId = "task-$index")
        }
        val result = MasterOperationsOverview.build(queue(events = events), handoffs(), agentLimit = 1000)
        assertEquals(20, result.recentAgents.size)
        assertEquals("task-25", result.recentAgents.first().taskId)
    }

    @Test fun agentWorkloadReflectsEnabledStateAndAssignedTaskStates() {
        val agents = listOf(
            pl.aimasterpro.app.agents.AgentSpec("reviewer", "Reviewer", "QA", "Review", listOf("review")),
            pl.aimasterpro.app.agents.AgentSpec("builder", "Builder", "Engineering", "Build", listOf("build"))
        )
        val result = MasterOperationsOverview.build(
            queue(tasks = listOf(
                task("task-running", MasterTaskQueueStore.Status.RUNNING, 30L).copy(assignedAgentIds = listOf("reviewer")),
                task("task-assigned", MasterTaskQueueStore.Status.ASSIGNED, 20L).copy(assignedAgentIds = listOf("reviewer", "builder")),
                task("task-blocked", MasterTaskQueueStore.Status.BLOCKED, 10L).copy(assignedAgentIds = listOf("builder"))
            )),
            handoffs(),
            agents = agents,
            enabledAgentIds = setOf("reviewer", "builder")
        )
        val reviewer = result.agentWorkloads.single { it.agentId == "reviewer" }
        assertEquals(MasterOperationsOverview.AgentAvailability.RUNNING, reviewer.availability)
        assertEquals(1, reviewer.runningTasks)
        assertEquals(1, reviewer.assignedTasks)
        assertEquals(setOf("task-running", "task-assigned"), reviewer.activeTaskIds.toSet())

        val builder = result.agentWorkloads.single { it.agentId == "builder" }
        assertEquals(MasterOperationsOverview.AgentAvailability.ASSIGNED, builder.availability)
        assertEquals(1, builder.assignedTasks)
        assertEquals(1, builder.blockedTasks)
    }

    @Test fun runningTaskCollisionAndDisabledAgentAssignmentAreExplicitConflicts() {
        val agents = listOf(
            pl.aimasterpro.app.agents.AgentSpec("reviewer", "Reviewer", "QA", "Review", listOf("review")),
            pl.aimasterpro.app.agents.AgentSpec("builder", "Builder", "Engineering", "Build", listOf("build"))
        )
        val result = MasterOperationsOverview.build(
            queue(tasks = listOf(
                task("run-one", MasterTaskQueueStore.Status.RUNNING, 30L).copy(assignedAgentIds = listOf("reviewer")),
                task("run-two", MasterTaskQueueStore.Status.RUNNING, 20L).copy(assignedAgentIds = listOf("reviewer")),
                task("disabled-task", MasterTaskQueueStore.Status.ASSIGNED, 10L).copy(assignedAgentIds = listOf("builder"))
            )),
            handoffs(),
            agents = agents,
            enabledAgentIds = setOf("reviewer")
        )
        assertEquals(MasterOperationsOverview.AgentAvailability.CONFLICTED,
            result.agentWorkloads.single { it.agentId == "reviewer" }.availability)
        assertEquals(MasterOperationsOverview.AgentAvailability.DISABLED,
            result.agentWorkloads.single { it.agentId == "builder" }.availability)
        assertTrue(result.agentConflicts.any {
            it.agentId == "reviewer" &&
                it.kind == MasterOperationsOverview.ConflictKind.MULTIPLE_RUNNING_TASKS &&
                it.taskIds.toSet() == setOf("run-one", "run-two")
        })
        assertTrue(result.agentConflicts.any {
            it.agentId == "builder" &&
                it.kind == MasterOperationsOverview.ConflictKind.DISABLED_AGENT_ASSIGNED
        })
    }

    @Test fun duplicateWorkDetectionRequiresExactLongObjectiveAndIgnoresTerminalTasks() {
        val objective = "Implement durable task recovery with explicit run identity and safe cancellation."
        val result = MasterOperationsOverview.build(
            queue(tasks = listOf(
                task("duplicate-a", MasterTaskQueueStore.Status.QUEUED).copy(objective = objective),
                task("duplicate-b", MasterTaskQueueStore.Status.RUNNING).copy(objective = "  " + objective.lowercase() + "  "),
                task("completed-copy", MasterTaskQueueStore.Status.COMPLETED).copy(objective = objective),
                task("short-a", MasterTaskQueueStore.Status.QUEUED).copy(objective = "Fix UI"),
                task("short-b", MasterTaskQueueStore.Status.QUEUED).copy(objective = "Fix UI")
            )),
            handoffs()
        )
        assertEquals(1, result.duplicateWorkGroups.size)
        assertEquals(setOf("duplicate-a", "duplicate-b"), result.duplicateWorkGroups.single().taskIds.toSet())
    }

    @Test fun unavailableAgentCatalogDoesNotClaimAgentsAreAvailable() {
        val result = MasterOperationsOverview.build(
            queue(tasks = listOf(task("task-running", MasterTaskQueueStore.Status.RUNNING))),
            handoffs(),
            agents = emptyList(),
            enabledAgentIds = emptySet(),
            agentCatalogAvailable = false
        )
        assertFalse(result.agentCatalogAvailable)
        assertTrue(result.agentWorkloads.isEmpty())
        assertTrue(result.agentConflicts.isEmpty())
        assertFalse(result.isComplete)
    }
}
