package pl.aimasterpro.app.core

/**
 * Read-only projection of one parent queue run into per-agent execution state.
 * All joins are fenced by both the parent run epoch and the deterministic execution id.
 */
object TaskRunObservability {
    data class AgentState(
        val agentId: String,
        val executionTaskId: String,
        val status: String,
        val detail: String,
        val updatedAt: Long,
        val providerId: String,
        val model: String,
        val reportAvailable: Boolean,
        val stale: Boolean
    )

    private val terminal = setOf("COMPLETED", "FAILED", "BLOCKED", "CANCELLED", "INTERRUPTED")
    private val active = setOf("QUEUED", "RUNNING", "REQUESTING", "USAGE_PENDING", "RESULT_PERSIST_PENDING")

    fun forTask(
        task: MasterTaskQueueStore.Task,
        queueEvents: List<MasterTaskQueueStore.Event>,
        checkpoints: List<TaskCheckpointStore.Event>,
        reportAgentIds: Set<String>,
        now: Long,
        staleAfterMs: Long = 5 * 60 * 1000L
    ): List<AgentState> {
        require(now >= 0L && staleAfterMs >= 0L)
        return task.assignedAgentIds.map { agentId ->
            val executionId = TaskResultArchive.executionTaskId(task.id, agentId, task.runId)
            val checkpoint = checkpoints.asSequence()
                .filter { it.taskId == executionId && it.runId == task.runId && it.agentId == agentId }
                .maxByOrNull { it.timestamp }
            val queueEvent = queueEvents.asSequence()
                .filter {
                    it.taskId == task.id && it.runId == task.runId &&
                        it.actor == "agent:$agentId" && it.operation in setOf("AGENT_PROGRESS", "AGENT_RESULT")
                }
                .maxByOrNull { it.timestamp }

            val useCheckpoint = checkpoint != null &&
                (queueEvent == null || checkpoint.timestamp >= queueEvent.timestamp)
            val status = when {
                useCheckpoint -> checkpoint!!.status
                queueEvent != null -> queueEvent.toStatus
                else -> "WAITING"
            }
            val detail = when {
                useCheckpoint -> checkpoint!!.detail
                queueEvent != null -> queueEvent.detail
                else -> "Agent nie opublikował jeszcze postępu w tym przebiegu."
            }
            val updatedAt = when {
                useCheckpoint -> checkpoint!!.timestamp
                queueEvent != null -> queueEvent.timestamp
                else -> 0L
            }
            val providerId = if (useCheckpoint) checkpoint!!.providerId else queueEvent?.providerId.orEmpty()
            val model = if (useCheckpoint) checkpoint!!.model else queueEvent?.model.orEmpty()
            val isStale = status in active && updatedAt > 0L && now >= updatedAt &&
                now - updatedAt > staleAfterMs

            AgentState(
                agentId = agentId,
                executionTaskId = executionId,
                status = status,
                detail = detail,
                updatedAt = updatedAt,
                providerId = providerId,
                model = model,
                reportAvailable = agentId in reportAgentIds,
                stale = isStale
            )
        }
    }
}
