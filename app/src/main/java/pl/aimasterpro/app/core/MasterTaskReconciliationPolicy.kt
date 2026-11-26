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
        val resultStatus: String = "",
        val checkpointStatus: String = ""
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
        UNASSIGNED_EVIDENCE,
        DUPLICATE_REPORT_EVIDENCE,
        DUPLICATE_RESULT_EVENT,
        CHECKPOINT_UNREADABLE,
        CHECKPOINT_RESULT_MISMATCH,
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
        val finalStatus: String? = null,
        val repairStatuses: Map<String, String> = emptyMap()
    )

    fun assess(
        taskStatus: String,
        taskRunId: Int,
        assignedAgentIds: List<String>,
        archiveReadable: Boolean,
        reports: List<Evidence>,
        resultEvents: List<Evidence>,
        checkpointJournalReadable: Boolean = true
    ): Assessment {
        if (!archiveReadable) return Assessment(Decision.ARCHIVE_UNREADABLE)
        if (taskRunId <= 0) return Assessment(Decision.INVALID_TASK_EPOCH)
        if (assignedAgentIds.isEmpty() || assignedAgentIds.size > 8 ||
            assignedAgentIds.any { it.isBlank() } || assignedAgentIds.distinct().size != assignedAgentIds.size) {
            return Assessment(Decision.NO_ASSIGNEES)
        }

        val assigned = assignedAgentIds.toSet()
        if ((reports + resultEvents).any { it.agentId !in assigned }) {
            return Assessment(Decision.UNASSIGNED_EVIDENCE)
        }
        val relevantReports = reports
        val relevantEvents = resultEvents
        val unexpected = (relevantReports + relevantEvents)
            .filter { it.runId != taskRunId }
            .map { it.agentId }.distinct().sorted()
        if (unexpected.isNotEmpty()) {
            return Assessment(Decision.EPOCH_MISMATCH, unexpectedEvidenceAgentIds = unexpected)
        }
        if (relevantReports.groupingBy { it.agentId }.eachCount().values.any { it > 1 }) {
            return Assessment(Decision.DUPLICATE_REPORT_EVIDENCE)
        }
        if (relevantEvents.groupingBy { it.agentId }.eachCount().values.any { it > 1 }) {
            return Assessment(Decision.DUPLICATE_RESULT_EVENT)
        }

        if (relevantEvents.any { it.resultStatus !in setOf("COMPLETED", "FAILED", "BLOCKED") }) {
            return Assessment(Decision.INVALID_RESULT_EVENT)
        }
        val eventByAgent = relevantEvents.associateBy { it.agentId }
        val checkpointConflict = relevantReports.any { report ->
            val checkpointStatus = report.checkpointStatus
            val eventStatus = eventByAgent[report.agentId]?.resultStatus.orEmpty()
            checkpointStatus in setOf("COMPLETED", "FAILED", "BLOCKED") &&
                eventStatus.isNotBlank() && checkpointStatus != eventStatus
        }
        if (checkpointConflict) return Assessment(Decision.CHECKPOINT_RESULT_MISMATCH)

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
            if (!checkpointJournalReadable) {
                return Assessment(
                    Decision.CHECKPOINT_UNREADABLE,
                    missingResultEventAgentIds = missingEvents
                )
            }
            val repairStatuses = missingEvents.mapNotNull { agentId ->
                val status = relevantReports.firstOrNull { it.agentId == agentId }?.checkpointStatus
                    ?.takeIf { it in setOf("COMPLETED", "FAILED", "BLOCKED") }
                status?.let { agentId to it }
            }.toMap()
            val allRepairable = repairStatuses.keys.containsAll(missingEvents)
            val aggregateStatuses = relevantEvents.map { it.resultStatus } + repairStatuses.values
            val repairFinalStatus = when {
                aggregateStatuses.any { it == "FAILED" } -> "FAILED"
                aggregateStatuses.any { it == "BLOCKED" } -> "BLOCKED"
                aggregateStatuses.size == assignedAgentIds.size -> "COMPLETED"
                else -> null
            }
            return Assessment(
                Decision.REPORTS_NEED_RESULT_EVENT_REPAIR,
                missingResultEventAgentIds = missingEvents,
                finalStatus = repairFinalStatus.takeIf { allRepairable },
                repairStatuses = repairStatuses
            )
        }

        val aggregateFinalStatus = when {
            relevantEvents.any { it.resultStatus == "FAILED" } -> "FAILED"
            relevantEvents.any { it.resultStatus == "BLOCKED" } -> "BLOCKED"
            else -> "COMPLETED"
        }
        return when (taskStatus) {
            "RUNNING" -> Assessment(
                Decision.READY_TO_FINALIZE,
                finalStatus = aggregateFinalStatus
            )
            "BLOCKED", "COMPLETED", "FAILED" ->
                if (taskStatus == aggregateFinalStatus) {
                    Assessment(Decision.TERMINAL_STATE_CONSISTENT, finalStatus = aggregateFinalStatus)
                } else {
                    Assessment(Decision.TERMINAL_STATE_INCONSISTENT, finalStatus = aggregateFinalStatus)
                }
            "QUEUED", "ASSIGNED", "PAUSED", "CANCELLED" ->
                Assessment(Decision.TERMINAL_STATE_INCONSISTENT, finalStatus = aggregateFinalStatus)
            else -> Assessment(Decision.NO_RECONCILIATION_REQUIRED)
        }
    }
}


/**
 * Reads durable stores and feeds only persisted evidence into the pure policy above.
 * assess() is inspection-only. repairMissingResultEvents() is a separate, explicit,
 * narrowly guarded repair path; neither path resumes work or replays provider requests.
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
        val checkpointReadable = TaskCheckpointStore.isJournalReadable(context)
        // Read the complete bounded journal, not latest(): latest() collapses duplicate task IDs and could hide conflicting agent identities before this reader can fail closed.
        val checkpointRows = if (checkpointReadable) {
            selectLatestCheckpointsForRun(TaskCheckpointStore.load(context), task.runId)
        } else emptyList()
        val checkpointByTaskId = checkpointRows.associateBy { it.taskId }
        val reports = if (archiveReadable) task.assignedAgentIds.mapNotNull { agentId ->
            TaskResultArchive.getExecution(context, task.id, agentId, task.runId)?.let { archived ->
                MasterTaskReconciliationPolicy.Evidence(
                    agentId, task.runId,
                    checkpointStatus = checkpointByTaskId[archived.taskId]
                        ?.takeIf { it.taskId == archived.taskId && it.agentId == agentId && it.runId == task.runId }
                        ?.status.orEmpty()
                )
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
            resultEvents = resultEvents,
            checkpointJournalReadable = checkpointReadable
        )
    }

    /**
     * Selects the newest checkpoint per execution only after fencing rows to the requested run.
     * Filtering after distinctBy would let a newer run hide the newest checkpoint from this run.
     */
    internal fun selectLatestCheckpointsForRun(
        rows: List<TaskCheckpointStore.Event>,
        runId: Int
    ): List<TaskCheckpointStore.Event> = rows.asSequence()
        .filter { it.runId == runId }
        .groupBy { it.taskId }
        .values
        .mapNotNull { executionRows ->
            // A single execution ID cannot legitimately belong to multiple agents. Do not
            // let journal order choose which identity wins when persisted evidence conflicts.
            if (executionRows.map { it.agentId }.distinct().size == 1) executionRows.first() else null
        }

    /**
     * Repairs only missing AGENT_RESULT events when both the exact-epoch report and a terminal
     * checkpoint are already durable. It never calls a provider and is safe to retry after a
     * partial repair because each write is fenced by the queue runId and duplicate events are
     * recognized from a fresh snapshot.
     */
    fun repairMissingResultEvents(context: Context, taskId: String): Boolean {
        val initial = MasterTaskQueueStore.snapshot(context)
        if (initial.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) return false
        val initialTask = initial.tasks.firstOrNull { it.id == taskId } ?: return false
        if (initialTask.status != MasterTaskQueueStore.Status.RUNNING) {
            return assess(context, initial, taskId).decision ==
                MasterTaskReconciliationPolicy.Decision.TERMINAL_STATE_CONSISTENT
        }
        val assessment = assess(context, initial, taskId)
        if (assessment.decision != MasterTaskReconciliationPolicy.Decision.REPORTS_NEED_RESULT_EVENT_REPAIR ||
            assessment.repairStatuses.isEmpty() ||
            assessment.repairStatuses.keys != assessment.missingResultEventAgentIds.toSet()) return false

        for (agentId in initialTask.assignedAgentIds) {
            val expectedStatus = assessment.repairStatuses[agentId] ?: continue
            val current = MasterTaskQueueStore.snapshot(context)
            if (current.status != MasterTaskQueueStore.ReadStatus.AVAILABLE) return false
            val task = current.tasks.firstOrNull { it.id == taskId } ?: return false
            if (task.runId != initialTask.runId) return false
            val existing = current.events.firstOrNull {
                it.taskId == taskId && it.actor == "agent:$agentId" &&
                    it.operation == "AGENT_RESULT" && it.runId == task.runId
            }
            if (existing != null) {
                if (existing.toStatus != expectedStatus) return false
                continue
            }
            if (task.status != MasterTaskQueueStore.Status.RUNNING ||
                !TaskResultArchive.isArchiveReadable(context) ||
                TaskResultArchive.getExecution(context, taskId, agentId, task.runId) == null ||
                !TaskCheckpointStore.isJournalReadable(context)) return false
            val archived = TaskResultArchive.getExecution(context, taskId, agentId, task.runId) ?: return false
            val checkpoint = TaskCheckpointStore.latest(context).firstOrNull {
                it.taskId == archived.taskId && it.agentId == agentId && it.runId == task.runId
            } ?: return false
            if (checkpoint.status != expectedStatus ||
                expectedStatus !in setOf("COMPLETED", "FAILED", "BLOCKED")) return false
            val queueStatus = runCatching { MasterTaskQueueStore.Status.valueOf(expectedStatus) }.getOrNull()
                ?: return false
            val recorded = MasterTaskQueueStore.recordAgentResult(
                context, taskId, agentId, queueStatus,
                "Odzyskano brakujące zdarzenie z trwałego raportu i checkpointu; bez ponownego żądania API.",
                expectedRunId = task.runId
            )
            if (!recorded) {
                val after = MasterTaskQueueStore.snapshot(context)
                val matching = after.status == MasterTaskQueueStore.ReadStatus.AVAILABLE &&
                    after.events.any {
                        it.taskId == taskId && it.actor == "agent:$agentId" &&
                            it.operation == "AGENT_RESULT" && it.runId == task.runId &&
                            it.toStatus == expectedStatus
                    }
                if (!matching) return false
            }
        }
        return true
    }
}
