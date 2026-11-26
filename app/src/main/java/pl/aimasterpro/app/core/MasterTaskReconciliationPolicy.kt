package pl.aimasterpro.app.core

/**
 * Pure consistency policy for reconciling a durable MASTER queue task with its execution reports.
 *
 * The caller must supply only evidence read from durable stores. This policy never starts work,
 * retries provider requests, writes events, or changes task state.
 */
object MasterTaskReconciliationPolicy {
    data class Evidence(
        val agentId: String,
        val runId: Int
    )

    enum class Decision {
        ARCHIVE_UNREADABLE,
        INVALID_TASK_EPOCH,
        NO_ASSIGNEES,
        EPOCH_MISMATCH,
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
        val unexpectedEvidenceAgentIds: List<String> = emptyList()
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

        val reportAgents = relevantReports.map { it.agentId }.toSet()
        val eventAgents = relevantEvents.map { it.agentId }.toSet()
        val missingReports = assignedAgentIds.filter { it !in reportAgents }
        val missingEvents = assignedAgentIds.filter { it !in eventAgents }

        if (missingReports.isNotEmpty() && missingEvents.any { it !in missingReports }) {
            return Assessment(
                Decision.RESULT_EVENTS_WITHOUT_REPORTS,
                missingReportAgentIds = missingReports,
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
            "RUNNING", "BLOCKED" -> Assessment(Decision.READY_TO_FINALIZE)
            "COMPLETED", "FAILED" -> Assessment(Decision.TERMINAL_STATE_CONSISTENT)
            "QUEUED", "ASSIGNED", "PAUSED", "CANCELLED" ->
                Assessment(Decision.TERMINAL_STATE_INCONSISTENT)
            else -> Assessment(Decision.NO_RECONCILIATION_REQUIRED)
        }
    }
}
