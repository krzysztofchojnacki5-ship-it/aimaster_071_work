package pl.aimasterpro.app.core

import pl.aimasterpro.app.agents.AgentSpec
import java.util.Locale

/**
 * Read-only, fail-closed projection for the MASTER command center.
 * It combines existing durable queue, handoff and agent-catalog snapshots without introducing another store.
 */
object MasterOperationsOverview {
    enum class AgentAvailability { AVAILABLE, ASSIGNED, RUNNING, CONFLICTED, DISABLED, UNKNOWN }
    enum class ConflictKind { MULTIPLE_RUNNING_TASKS, DISABLED_AGENT_ASSIGNED }

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

    data class AgentWorkload(
        val agentId: String,
        val name: String,
        val category: String,
        val enabled: Boolean,
        val availability: AgentAvailability,
        val queuedTasks: Int,
        val assignedTasks: Int,
        val runningTasks: Int,
        val blockedTasks: Int,
        val activeTaskIds: List<String>,
        val latestActivityStatus: String,
        val providerLabel: String,
        val lastActivityAt: Long
    )

    data class AgentConflict(
        val agentId: String,
        val kind: ConflictKind,
        val taskIds: List<String>
    )

    data class DuplicateWorkGroup(
        val taskIds: List<String>,
        val title: String,
        val statusLabels: List<String>
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
        val recentAgents: List<AgentPulse>,
        val agentCatalogAvailable: Boolean,
        val agentWorkloads: List<AgentWorkload>,
        val agentConflicts: List<AgentConflict>,
        val duplicateWorkGroups: List<DuplicateWorkGroup>
    ) {
        val isComplete: Boolean
            get() = queueStatus == MasterTaskQueueStore.ReadStatus.AVAILABLE &&
                handoffStatus == AgentHandoffStore.ReadStatus.AVAILABLE && agentCatalogAvailable
    }

    fun build(
        queue: MasterTaskQueueStore.Snapshot,
        handoffs: AgentHandoffStore.Snapshot,
        taskLimit: Int = 5,
        agentLimit: Int = 6,
        agents: List<AgentSpec> = emptyList(),
        enabledAgentIds: Set<String> = agents.map { it.id }.toSet(),
        agentCatalogAvailable: Boolean = true
    ): Snapshot {
        val queueAvailable = queue.status == MasterTaskQueueStore.ReadStatus.AVAILABLE
        val handoffsAvailable = handoffs.status == AgentHandoffStore.ReadStatus.AVAILABLE
        val counts = if (queueAvailable) {
            MasterTaskQueueStore.Status.values().associateWith { status ->
                queue.tasks.count { it.status == status }
            }
        } else emptyMap()
        val activeStatuses = setOf(
            MasterTaskQueueStore.Status.QUEUED,
            MasterTaskQueueStore.Status.ASSIGNED,
            MasterTaskQueueStore.Status.RUNNING,
            MasterTaskQueueStore.Status.BLOCKED,
            MasterTaskQueueStore.Status.PAUSED
        )
        val activeQueueTasks = if (queueAvailable) queue.tasks.filter { it.status in activeStatuses } else emptyList()
        val activeTasks = if (queueAvailable) {
            queue.tasks.asSequence()
                .filter { it.status == MasterTaskQueueStore.Status.RUNNING ||
                    it.status == MasterTaskQueueStore.Status.BLOCKED }
                .sortedWith(compareByDescending<MasterTaskQueueStore.Task> { it.updatedAt }.thenBy { it.id })
                .take(taskLimit.coerceIn(1, 20))
                .map { TaskLine(it.id, it.title, it.status, it.runId, it.currentStep, it.updatedAt) }
                .toList()
        } else emptyList()

        val agentWorkloads = if (queueAvailable && agentCatalogAvailable) {
            agents.distinctBy { it.id }.sortedBy { it.name.lowercase(Locale.ROOT) }.map { agent ->
                val assigned = activeQueueTasks.filter { agent.id in it.assignedAgentIds }
                val running = assigned.count { it.status == MasterTaskQueueStore.Status.RUNNING }
                val enabled = agent.id in enabledAgentIds
                val runningIds = assigned.filter { it.status == MasterTaskQueueStore.Status.RUNNING }.map { it.id }
                val latestActivity = queue.events.asSequence()
                    .filter { it.actor == "agent:${agent.id}" }
                    .filter { event -> assigned.any { it.id == event.taskId && it.runId == event.runId } }
                    .maxWithOrNull(compareBy<MasterTaskQueueStore.Event> { it.timestamp }.thenBy { it.id })
                val providerLabel = latestActivity?.let { event ->
                    listOf(event.providerId, event.model).filter { it.isNotBlank() }.joinToString(" / ")
                }.orEmpty()
                val availability = when {
                    !enabled -> AgentAvailability.DISABLED
                    runningIds.size > 1 -> AgentAvailability.CONFLICTED
                    running > 0 -> AgentAvailability.RUNNING
                    assigned.isNotEmpty() -> AgentAvailability.ASSIGNED
                    else -> AgentAvailability.AVAILABLE
                }
                AgentWorkload(
                    agentId = agent.id,
                    name = agent.name,
                    category = agent.category,
                    enabled = enabled,
                    availability = availability,
                    queuedTasks = assigned.count { it.status == MasterTaskQueueStore.Status.QUEUED },
                    assignedTasks = assigned.count { it.status == MasterTaskQueueStore.Status.ASSIGNED },
                    runningTasks = running,
                    blockedTasks = assigned.count {
                        it.status == MasterTaskQueueStore.Status.BLOCKED ||
                            it.status == MasterTaskQueueStore.Status.PAUSED
                    },
                    activeTaskIds = assigned.map { it.id }.sorted(),
                    latestActivityStatus = latestActivity?.toStatus?.ifBlank { latestActivity.operation }.orEmpty(),
                    providerLabel = providerLabel,
                    lastActivityAt = latestActivity?.timestamp ?: 0L
                )
            }
        } else emptyList()

        val agentConflicts = if (queueAvailable && agentCatalogAvailable) {
            buildList {
                agentWorkloads.forEach { workload ->
                    val runningIds = activeQueueTasks.filter {
                        it.status == MasterTaskQueueStore.Status.RUNNING && workload.agentId in it.assignedAgentIds
                    }.map { it.id }.sorted()
                    if (runningIds.size > 1) add(
                        AgentConflict(workload.agentId, ConflictKind.MULTIPLE_RUNNING_TASKS, runningIds)
                    )
                    if (!workload.enabled && workload.activeTaskIds.isNotEmpty()) add(
                        AgentConflict(workload.agentId, ConflictKind.DISABLED_AGENT_ASSIGNED, workload.activeTaskIds)
                    )
                }
            }
        } else emptyList()

        val duplicateWorkGroups = if (queueAvailable) {
            activeQueueTasks.mapNotNull { task ->
                val normalized = task.objective.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
                if (normalized.length < 40) null else normalized to task
            }.groupBy({ it.first }, { it.second })
                .values.filter { it.size > 1 }
                .map { tasks ->
                    val ordered = tasks.sortedWith(compareBy<MasterTaskQueueStore.Task> { it.createdAt }.thenBy { it.id })
                    DuplicateWorkGroup(
                        taskIds = ordered.map { it.id },
                        title = ordered.first().title,
                        statusLabels = ordered.map { it.status.name }
                    )
                }.sortedWith(compareByDescending<DuplicateWorkGroup> { it.taskIds.size }.thenBy { it.title })
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
            recentAgents = agentEvents,
            agentCatalogAvailable = agentCatalogAvailable,
            agentWorkloads = agentWorkloads,
            agentConflicts = agentConflicts,
            duplicateWorkGroups = duplicateWorkGroups
        )
    }
}
