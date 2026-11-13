package pl.aimasterpro.app.agents

/**
 * Builds a bounded, synthesis-only context from successful specialist reports.
 * Failed/blocked runs are operational diagnostics, not evidence for the final answer.
 */
object SynthesisContextBuilder {
    data class Result(
        val text: String,
        val includedReports: Int,
        val excludedReports: Int,
        val truncatedReports: Int,
        val sourceChars: Int
    )

    fun build(
        results: List<TeamTaskResult>,
        maxPerReportChars: Int = 6_000,
        maxTotalChars: Int = 18_000
    ): Result {
        require(maxPerReportChars > 0) { "maxPerReportChars musi być większe od zera" }
        require(maxTotalChars > 0) { "maxTotalChars musi być większe od zera" }

        val completed = results.filter {
            it.status == TeamTaskResult.Status.COMPLETED && it.report.isNotBlank()
        }
        val excluded = results.size - completed.size
        if (completed.isEmpty()) return Result("", 0, excluded, 0, 0)

        val sourceChars = completed.sumOf { it.report.length }
        val out = StringBuilder()
        var truncated = 0
        var included = 0
        completed.forEachIndexed { index, item ->
            val separator = if (out.isNotEmpty()) "\n\n" else ""
            val remaining = (maxTotalChars - out.length - separator.length).coerceAtLeast(0)
            if (remaining <= 0) {
                truncated += completed.size - index
                return@forEachIndexed
            }
            val sourceLabel = if (item.reviewOfAgentId.isBlank()) item.agentId
                else "Peer Review: ${item.agentId} → ${item.reviewOfAgentId}"
            val header = "### $sourceLabel • ${item.taskId}\n"
            val remainingReports = completed.size - index
            val fairShare = remaining / remainingReports
            val bodyBudget = (fairShare - header.length).coerceAtLeast(0)
                .coerceAtMost(maxPerReportChars)
            if (bodyBudget == 0) {
                truncated++
                return@forEachIndexed
            }
            val report = item.report.trim()
            val body = if (report.length > bodyBudget) {
                truncated++
                val marker = "\n[Raport skrócony ze względu na limit kontekstu syntezy.]"
                if (bodyBudget > marker.length) report.take(bodyBudget - marker.length) + marker else report.take(bodyBudget)
            } else report
            val chunk = header + body
            val allowed = (maxTotalChars - out.length - separator.length).coerceAtLeast(0)
            out.append(separator)
            val appended = chunk.take(allowed)
            out.append(appended)
            if (appended.contains("\n") && appended.substringAfter("\n").isNotBlank()) included++
            if (chunk.length > allowed) truncated++
        }
        return Result(out.toString().take(maxTotalChars), included, excluded + (completed.size - included), truncated, sourceChars)
    }
}
