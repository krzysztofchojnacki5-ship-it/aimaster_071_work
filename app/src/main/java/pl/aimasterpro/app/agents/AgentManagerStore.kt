package pl.aimasterpro.app.agents

import android.content.Context
import pl.aimasterpro.app.core.ApiTokenTracker

class AgentManagerStore(private val context: Context) {

    fun getSummary(): AgentManagerSummary {
        val allSpecs = AgentCatalog.all
        val records = mutableListOf<AgentExecutionRecord>()

        var running = 0
        var completed = 0
        var failed = 0
        var totalTokens = 0L

        allSpecs.take(12).forEachIndexed { index, spec ->
            val status = when (index % 5) {
                0 -> AgentExecutionStatus.COMPLETED
                1 -> AgentExecutionStatus.RUNNING
                2 -> AgentExecutionStatus.QUEUED
                3 -> AgentExecutionStatus.FAILED
                else -> AgentExecutionStatus.CANCELLED
            }
            if (status == AgentExecutionStatus.RUNNING) running++
            if (status == AgentExecutionStatus.COMPLETED) completed++
            if (status == AgentExecutionStatus.FAILED) failed++

            val tokens = (index + 1) * 142
            totalTokens += tokens

            records.add(
                AgentExecutionRecord(
                    executionId = "exec-${spec.id}-$index",
                    agentId = spec.id,
                    agentName = spec.name,
                    category = spec.category,
                    status = status,
                    taskId = "task-${100 + index}",
                    chatId = "chat-${200 + index}",
                    provider = if (index % 2 == 0) "gemini" else "openrouter",
                    model = if (index % 2 == 0) "gemini-1.5-pro" else "anthropic/claude-3.5-sonnet",
                    tokensUsed = tokens,
                    contextLimit = 128_000,
                    durationMs = (index + 1) * 1250L,
                    lastLog = "Agent ${spec.name} initialized and verified role constraints.",
                    errorMsg = if (status == AgentExecutionStatus.FAILED) "API rate limit or timeout exceeded" else null
                )
            )
        }

        return AgentManagerSummary(
            totalAgents = allSpecs.size,
            runningAgents = running,
            completedAgents = completed,
            failedAgents = failed,
            totalTokensConsumed = totalTokens,
            activeExecutions = records
        )
    }

    fun controlAgent(executionId: String, action: String): Boolean {
        return executionId.isNotBlank() && action.isNotBlank()
    }
}
