package pl.aimasterpro.app.agents

import pl.aimasterpro.app.core.MasterTaskQueueStore

/**
 * Builds a fenced assignment proposal for one durable MASTER queue item.
 * It is deliberately side-effect free; the caller commits through assignPlanned().
 */
object MasterQueueAssignmentPlanner {
    data class Decision(
        val taskId: String,
        val expectedUpdatedAt: Long,
        val expectedRunId: Int,
        val selection: MasterAgentCoordinator.Plan,
        val rejectionReason: String = ""
    ) {
        val isAssignable: Boolean
            get() = rejectionReason.isBlank() && selection.isAssignable

        val selectedAgentIds: List<String>
            get() = selection.selectedAgents.map { it.id }
    }

    fun plan(
        queue: MasterTaskQueueStore.Snapshot,
        taskId: String,
        enabledCatalog: List<AgentSpec>,
        providerReadyAgentIds: Set<String>,
        maxAgents: Int = 4,
        maxActiveTasksPerAgent: Int = 1
    ): Decision {
        fun rejected(reason: String): Decision = Decision(
            taskId = taskId,
            expectedUpdatedAt = -1L,
            expectedRunId = -1,
            selection = MasterAgentCoordinator.Plan(
                emptyList(), emptyList(), emptyMap(), reason
            ),
            rejectionReason = reason
        )

        if (queue.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) {
            return rejected("QUEUE_UNAVAILABLE")
        }
        val task = queue.tasks.firstOrNull { it.id == taskId } ?: return rejected("TASK_NOT_FOUND")
        if (task.status !in setOf(
                MasterTaskQueueStore.Status.QUEUED,
                MasterTaskQueueStore.Status.PAUSED
            )) return rejected("TASK_NOT_ASSIGNABLE")
        if (enabledCatalog.isEmpty()) return rejected("NO_ENABLED_AGENTS")

        val activeAssignments = queue.tasks.asSequence()
            .filter { it.status in setOf(
                MasterTaskQueueStore.Status.ASSIGNED,
                MasterTaskQueueStore.Status.RUNNING
            ) }
            .map { it.assignedAgentIds }
            .toList()

        val selection = MasterAgentCoordinator.planFromSnapshots(
            objective = task.objective,
            catalog = enabledCatalog,
            enabledAgentIds = enabledCatalog.map { it.id }.toSet(),
            providerReadyAgentIds = providerReadyAgentIds,
            activeAssignments = activeAssignments,
            maxAgents = maxAgents,
            maxActiveTasksPerAgent = maxActiveTasksPerAgent
        )
        return Decision(
            taskId = task.id,
            expectedUpdatedAt = task.updatedAt,
            expectedRunId = task.runId,
            selection = selection
        )
    }
}
