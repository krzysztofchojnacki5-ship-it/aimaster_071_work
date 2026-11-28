package pl.aimasterpro.app.network

/**
 * A single user/assistant turn sent to a provider as conversation context.
 * System prompts and internal application events are deliberately not represented here.
 */
data class ConversationTurn(
    val role: String,
    val content: String
)

/** Builds a bounded, provider-neutral snapshot of the recent visible conversation. */
object ConversationContextBuilder {
    const val DEFAULT_MAX_HISTORY_CHARS = 12_000

    fun build(
        history: List<ConversationTurn>,
        currentUserText: String,
        maxHistoryChars: Int = DEFAULT_MAX_HISTORY_CHARS
    ): List<ConversationTurn> {
        val normalized = history.mapNotNull { turn ->
            val role = turn.role.trim().lowercase()
            val content = turn.content.trim()
            if (role !in setOf("user", "assistant") || content.isBlank()) null
            else ConversationTurn(role, content)
        }

        // MainActivity persists the pending user turn before the network request. Be defensive
        // for other callers too: if that exact turn is already the final history item, send it
        // only once as the current message appended by ProviderClient.
        val withoutPendingCurrent = if (
            normalized.lastOrNull()?.role == "user" &&
            normalized.lastOrNull()?.content == currentUserText.trim()
        ) normalized.dropLast(1) else normalized

        var remaining = maxHistoryChars.coerceAtLeast(0)
        val newestFirst = mutableListOf<ConversationTurn>()
        for (turn in withoutPendingCurrent.asReversed()) {
            if (remaining <= 0) break
            val boundedContent = if (turn.content.length <= remaining) {
                turn.content
            } else {
                takeLastWithoutSplittingSurrogatePair(turn.content, remaining)
            }
            if (boundedContent.isNotBlank()) {
                newestFirst += ConversationTurn(turn.role, boundedContent)
                remaining -= boundedContent.length
            }
        }
        return newestFirst.asReversed()
    }
}
