package pl.aimasterpro.app.core

import android.content.Context

/**
 * Pure consistency policy for reconciling a durable MASTER queue task with its execution reports.
 *
 * The caller must supply only evidence read from durable stores. This policy never starts work,
 * retries provider requests, writes events, or changes task state.
 */
object MasterTaskReconciliationPolicy {
    data class Evidence(
        val agentId: String,
        val runId: Int,
        val resultStatus: String = ""
    )

    enum class Decision {
        QUEUE_UNAVAILABLE,
        QUEUE_CORRUPT,
        TASK_NOT_FOUND,
        ARCHIVE_UNREADABLE,
        INVALID_TASK_EPOCH,
        NO_ASSIGNEES,
        EPOCH_MISMATCH,
        INVALID_RESULT_EVENT,
        MISSING_REPORTS,
        REPORTS_NEED_RESULT_EVENT_REPAIR,
        RESULT_EVENTS_WITHOUT_REPORTS,
        READY_TO_FINALIZE,
        TERMINAL_STATE_CONSISTENT,
        TERMINAL_STATE_INCONSISTENT,
        NO_RECONCILIATION_REQUIRED
    }

    data class Assessment(
        val decision: Decision,
        val missingReportAgentIds: List<String> = emptyList(),
        val missingResultEventAgentIds: List<String> = emptyList(),
        val unexpectedEvidenceAgentIds: List<String> = emptyList(),
        val finalStatus: String? = null
    )

    fun assess(
        taskStatus: String,
        taskRunId: Int,
        assignedAgentIds: List<String>,
        archiveReadable: Boolean,
        reports: List<Evidence>,
        resultEvents: List<Evidence>
    ): Assessment {
        if (!archiveReadable) return Assessment(Decision.ARCHIVE_UNREADABLE)
        if (taskRunId <= 0) return Assessment(Decision.INVALID_TASK_EPOCH)
        if (assignedAgentIds.isEmpty() || assignedAgentIds.size > 8 ||
            assignedAgentIds.any { it.isBlank() } || assignedAgentIds.distinct().size != assignedAgentIds.size) {
            return Assessment(Decision.NO_ASSIGNEES)
        }

        val assigned = assignedAgentIds.toSet()
        val relevantReports = reports.filter { it.agentId in assigned }
        val relevantEvents = resultEvents.filter { it.agentId in assigned }
        val unexpected = (relevantReports + relevantEvents)
            .filter { it.runId != taskRunId }
            .map { it.agentId }.distinct().sorted()
        if (unexpected.isNotEmpty()) {
            return Assessment(Decision.EPOCH_MISMATCH, unexpectedEvidenceAgentIds = unexpected)
        }

        if (relevantEvents.any { it.resultStatus !in setOf("COMPLETED", "FAILED", "BLOCKED") }) {
            return Assessment(Decision.INVALID_RESULT_EVENT)
        }
        val reportAgents = relevantReports.map { it.agentId }.toSet()
        val eventAgents = relevantEvents.map { it.agentId }.toSet()
        val missingReports = assignedAgentIds.filter { it !in reportAgents }
        val missingEvents = assignedAgentIds.filter { it !in eventAgents }

        val orphanEventAgents = assignedAgentIds.filter { it in eventAgents && it !in reportAgents }
        if (orphanEventAgents.isNotEmpty()) {
            return Assessment(
                Decision.RESULT_EVENTS_WITHOUT_REPORTS,
                missingReportAgentIds = orphanEventAgents,
                missingResultEventAgentIds = missingEvents
            )
        }
        if (missingReports.isNotEmpty()) {
            return Assessment(Decision.MISSING_REPORTS, missingReportAgentIds = missingReports)
        }
        if (missingEvents.isNotEmpty()) {
            return Assessment(
                Decision.REPORTS_NEED_RESULT_EVENT_REPAIR,
                missingResultEventAgentIds = missingEvents
            )
        }

        return when (taskStatus) {
            "RUNNING", "BLOCKED" -> Assessment(
                Decision.READY_TO_FINALIZE,
                finalStatus = when {
                    relevantEvents.any { it.resultStatus == "FAILED" } -> "FAILED"
                    relevantEvents.any { it.resultStatus == "BLOCKED" } -> "BLOCKED"
                    else -> "COMPLETED"
                }
            )
            "COMPLETED", "FAILED" -> Assessment(Decision.TERMINAL_STATE_CONSISTENT)
            "QUEUED", "ASSIGNED", "PAUSED", "CANCELLED" ->
                Assessment(Decision.TERMINAL_STATE_INCONSISTENT)
            else -> Assessment(Decision.NO_RECONCILIATION_REQUIRED)
        }
    }
}


/**
 * Reads the durable stores and feeds only persisted evidence into the pure policy above.
 * This is inspection-only: it does not repair, finalize, resume, or replay any work.
 */
object MasterTaskReconciliationReader {
    fun assess(context: Context, snapshot: MasterTaskQueueStore.Snapshot, taskId: String):
        MasterTaskReconciliationPolicy.Assessment {
        if (snapshot.status == MasterTaskQueueStore.ReadStatus.UNAVAILABLE) {
            return MasterTaskReconciliationPolicy.Assessment(
                MasterTaskReconciliationPolicy.Decision.QUEUE_UNAVAILABLE
            )
        }
        if (snapshot.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) {
            return MasterTaskReconciliationPolicy.Assessment(
                MasterTaskReconciliationPolicy.Decision.QUEUE_CORRUPT
            )
        }
        val task = snapshot.tasks.firstOrNull { it.id == taskId }
            ?: return MasterTaskReconciliationPolicy.Assessment(
                MasterTaskReconciliationPolicy.Decision.TASK_NOT_FOUND
            )
        val archiveReadable = TaskResultArchive.isArchiveReadable(context)
        val reports = if (archiveReadable) task.assignedAgentIds.mapNotNull { agentId ->
            TaskResultArchive.getExecution(context, task.id, agentId, task.runId)?.let {
                MasterTaskReconciliationPolicy.Evidence(agentId, task.runId)
            }
        } else emptyList()
        val resultEvents = snapshot.events.asSequence()
            .filter { it.taskId == task.id && it.operation == "AGENT_RESULT" }
            .mapNotNull { event ->
                val agentId = event.actor.removePrefix("agent:")
                if (!event.actor.startsWith("agent:")) null
                else MasterTaskReconciliationPolicy.Evidence(agentId, event.runId, event.toStatus)
            }.toList()
        return MasterTaskReconciliationPolicy.assess(
            taskStatus = task.status.name,
            taskRunId = task.runId,
            assignedAgentIds = task.assignedAgentIds,
            archiveReadable = archiveReadable,
            reports = reports,
            resultEvents = resultEvents
        )
    }
}
