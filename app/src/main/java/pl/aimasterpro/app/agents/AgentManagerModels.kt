package pl.aimasterpro.app.agents

enum class AgentExecutionStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED
}

data class AgentExecutionRecord(
    val executionId: String,
    val agentId: String,
    val agentName: String,
    val category: String,
    val status: AgentExecutionStatus,
    val taskId: String?,
    val chatId: String?,
    val provider: String?,
    val model: String?,
    val tokensUsed: Int,
    val contextLimit: Int?,
    val durationMs: Long,
    val lastLog: String,
    val errorMsg: String?
)

data class AgentManagerSummary(
    val totalAgents: Int,
    val runningAgents: Int,
    val completedAgents: Int,
    val failedAgents: Int,
    val totalTokensConsumed: Long,
    val activeExecutions: List<AgentExecutionRecord>
)
