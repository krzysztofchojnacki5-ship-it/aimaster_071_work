package pl.aimasterpro.app.core

/** Resolves only the exact ChangeSet explicitly selected by durable host state. */
object HostActiveEvidenceScopeResolver {
    data class Candidate(
        val changeSet: MasterEvidenceContextBuilder.Candidate,
        val status: String
    )

    enum class Status { SELECTED, NONE, INVALID }
    data class Result(val status: Status, val selected: MasterEvidenceContextBuilder.Candidate? = null)

    /** No candidate ordering or recency heuristic is permitted. */
    fun select(activeChatId: String, activeChangeSetId: String?, candidates: List<Candidate>): Result {
        if (activeChatId.isBlank()) return Result(Status.INVALID)
        if (activeChangeSetId.isNullOrBlank()) return Result(Status.NONE)
        val matches = candidates.filter { it.changeSet.changeSetId == activeChangeSetId }
        val exact = matches.singleOrNull() ?: return Result(Status.INVALID)
        val scope = exact.changeSet
        if (scope.chatId != activeChatId || scope.taskId.isBlank() || scope.changeSetId.isBlank() ||
            scope.snapshotId.isBlank() || exact.status !in ELIGIBLE_STATUSES) return Result(Status.INVALID)
        return Result(Status.SELECTED, scope)
    }

    private val ELIGIBLE_STATUSES = setOf("APPLIED_UNVERIFIED", "APPLIED")
}
