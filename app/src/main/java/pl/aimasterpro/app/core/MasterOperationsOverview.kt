package pl.aimasterpro.app.core

/**
 * Read-only, fail-closed projection for the MASTER command center.
 * It combines existing durable queue and handoff snapshots without introducing another store.
 */
object MasterOperationsOverview {
    data class TaskLine(
        val id: String,
        val title: String,
        val status: MasterTaskQueueStore.Status,
        val runId: Int,
        val currentStep: String,
        val updatedAt: Long
    )

    data class AgentPulse(
        val taskId: String,
        val runId: Int,
        val agentId: String,
        val status: String,
        val providerLabel: String,
        val timestamp: Long
    )

    data class Snapshot(
        val queueStatus: MasterTaskQueueStore.ReadStatus,
        val handoffStatus: AgentHandoffStore.ReadStatus,
        val totalTasks: Int?,
        val taskCounts: Map<MasterTaskQueueStore.Status, Int>,
        val activeTasks: List<TaskLine>,
        val pendingHandoffs: Int?,
        val claimedHandoffs: Int?,
        val rejectedHandoffs: Int?,
        val recentAgents: List<AgentPulse>
    ) {
        val isComplete: Boolean
            get() = queueStatus == MasterTaskQueueStore.ReadStatus.AVAILABLE &&
                handoffStatus == AgentHandoffStore.ReadStatus.AVAILABLE
    }

    fun build(
        queue: MasterTaskQueueStore.Snapshot,
        handoffs: AgentHandoffStore.Snapshot,
        taskLimit: Int = 5,
        agentLimit: Int = 6
    ): Snapshot {
        val queueAvailable = queue.status == MasterTaskQueueStore.ReadStatus.AVAILABLE
        val handoffsAvailable = handoffs.status == AgentHandoffStore.ReadStatus.AVAILABLE
        val counts = if (queueAvailable) {
            MasterTaskQueueStore.Status.values().associateWith { status ->
                queue.tasks.count { it.status == status }
            }
        } else emptyMap()
        val activeTasks = if (queueAvailable) {
            queue.tasks.asSequence()
                .filter { it.status == MasterTaskQueueStore.Status.RUNNING ||
                    it.status == MasterTaskQueueStore.Status.BLOCKED }
                .sortedWith(compareByDescending<MasterTaskQueueStore.Task> { it.updatedAt }.thenBy { it.id })
                .take(taskLimit.coerceIn(1, 20))
                .map { TaskLine(it.id, it.title, it.status, it.runId, it.currentStep, it.updatedAt) }
                .toList()
        } else emptyList()

        val agentEvents = if (queueAvailable) queue.events.asSequence()
            .filter { it.actor.startsWith("agent:", ignoreCase = true) }
            .filter { it.operation in setOf("AGENT_PROGRESS", "AGENT_RESULT", "AGENT_MESSAGE") }
            .groupBy { Triple(it.taskId, it.runId, it.actor.removePrefix("agent:")) }
            .values.mapNotNull { events ->
                events.maxWithOrNull(compareBy<MasterTaskQueueStore.Event> { it.timestamp }.thenBy { it.id })
            }.sortedWith(compareByDescending<MasterTaskQueueStore.Event> { it.timestamp }.thenBy { it.id })
            .take(agentLimit.coerceIn(1, 20))
            .map { event ->
                AgentPulse(
                    taskId = event.taskId,
                    runId = event.runId,
                    agentId = event.actor.removePrefix("agent:"),
                    status = event.toStatus.ifBlank { event.operation },
                    providerLabel = listOf(event.providerId, event.model).filter { it.isNotBlank() }.joinToString(" / "),
                    timestamp = event.timestamp
                )
            }.toList() else emptyList()

        return Snapshot(
            queueStatus = queue.status,
            handoffStatus = handoffs.status,
            totalTasks = if (queueAvailable) queue.tasks.size else null,
            taskCounts = counts,
            activeTasks = activeTasks,
            pendingHandoffs = if (handoffsAvailable) handoffs.messages.count {
                it.state == AgentHandoffStore.State.PENDING
            } else null,
            claimedHandoffs = if (handoffsAvailable) handoffs.messages.count {
                it.state == AgentHandoffStore.State.CLAIMED
            } else null,
            rejectedHandoffs = if (handoffsAvailable) handoffs.messages.count {
                it.state == AgentHandoffStore.State.REJECTED
            } else null,
            recentAgents = agentEvents
        )
    }
}
