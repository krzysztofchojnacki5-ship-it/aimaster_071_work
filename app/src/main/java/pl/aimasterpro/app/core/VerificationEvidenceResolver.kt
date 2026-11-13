package pl.aimasterpro.app.core

/**
 * Builds a fail-closed, exact-scope evidence bundle for MASTER synthesis.
 *
 * The resolver never widens scope, never promotes agent-authored claims, and accepts a
 * TOOL_EXECUTION result only when the ledger also contains the host-created NOT_RUN
 * start marker carrying the same executionId and exact task/chat/snapshot scope.
 * This is a consistency boundary over the local ledger, not a cryptographic attestation
 * against a compromised app process or a modified local preferences store.
 */
class VerificationEvidenceResolver(private val store: VerificationEvidenceStore) {
    enum class ResolutionStatus {
        READY,
        INVALID_REQUEST,
        LEDGER_INVALID
    }

    data class Scope(
        val taskId: String,
        val chatId: String,
        val snapshotId: String
    )

    data class Bundle(
        val status: ResolutionStatus,
        val scope: Scope?,
        val assessment: VerificationAssessment?,
        /** Evidence safe to pass to the verification assessment / MASTER evidence context. */
        val verifiedEvidence: List<VerificationEvidence>,
        /** Claims remain visible for context but can never satisfy required checks. */
        val agentClaims: List<VerificationEvidence>,
        val latestByKind: Map<VerificationEvidenceKind, VerificationEvidence>,
        val rejectedUnpairedExecutionCount: Int,
        val errorCode: String = ""
    ) {
        val isUsable: Boolean
            get() = status == ResolutionStatus.READY && assessment != null
    }

    fun resolve(
        taskId: String,
        chatId: String,
        snapshotId: String,
        requiredKinds: Set<VerificationEvidenceKind>
    ): Bundle {
        if (taskId.isBlank() || chatId.isBlank() || snapshotId.isBlank() || requiredKinds.isEmpty()) {
            return empty(ResolutionStatus.INVALID_REQUEST, null, "SCOPE_OR_REQUIREMENTS_EMPTY")
        }
        val scope = Scope(taskId, chatId, snapshotId)
        val loaded = store.forScope(taskId, chatId, snapshotId)
        if (!loaded.valid) return empty(ResolutionStatus.LEDGER_INVALID, scope, loaded.errorCode.ifBlank { "LEDGER_INVALID" })

        // Defend the resolver even if a future store implementation accidentally widens lookup.
        val exact = loaded.entries.filter {
            it.taskId == taskId && it.chatId == chatId && it.snapshotId == snapshotId
        }
        val claims = exact.filter { it.origin == VerificationEvidenceOrigin.AGENT_CLAIM }
        val nonClaims = exact.filterNot { it.origin == VerificationEvidenceOrigin.AGENT_CLAIM }

        // A completed tool result is trusted only when its host-generated start marker exists.
        // Old V1/V2 completions without a matching marker remain visible in storage but unresolved.
        val startedAtByExecution = nonClaims.asSequence()
            .filter { it.origin == VerificationEvidenceOrigin.NOT_RUN && it.executionId.isNotBlank() }
            .groupBy { it.executionId to it.kind }
            .mapValues { (_, markers) -> markers.minOf { it.observedAtEpochMs } }
        val unpaired = nonClaims.filter { row ->
            if (row.origin != VerificationEvidenceOrigin.TOOL_EXECUTION) false
            else {
                val startedAt = startedAtByExecution[row.executionId to row.kind]
                startedAt == null || row.observedAtEpochMs <= startedAt
            }
        }
        val pairedIds = nonClaims.filter { row ->
            if (row.origin != VerificationEvidenceOrigin.TOOL_EXECUTION) false
            else {
                val startedAt = startedAtByExecution[row.executionId to row.kind]
                startedAt != null && row.observedAtEpochMs > startedAt
            }
        }.map { it.id }.toSet()
        val accepted = nonClaims.filter { row ->
            row.origin != VerificationEvidenceOrigin.TOOL_EXECUTION || row.id in pairedIds
        }

        val latest = accepted.groupBy { it.kind }.mapValues { (_, rows) ->
            rows.maxWithOrNull(compareBy<VerificationEvidence> { it.observedAtEpochMs }.thenBy { it.id })!!
        }
        val assessment = VerificationAssessment.assess(latest.values.toList(), requiredKinds)
        return Bundle(
            status = ResolutionStatus.READY,
            scope = scope,
            assessment = assessment,
            verifiedEvidence = latest.values.sortedWith(
                compareBy<VerificationEvidence> { it.kind.name }.thenBy { it.id }
            ),
            agentClaims = claims.sortedWith(
                compareBy<VerificationEvidence> { it.observedAtEpochMs }.thenBy { it.id }
            ),
            latestByKind = latest,
            rejectedUnpairedExecutionCount = unpaired.size
        )
    }

    private fun empty(status: ResolutionStatus, scope: Scope?, error: String) = Bundle(
        status = status,
        scope = scope,
        assessment = null,
        verifiedEvidence = emptyList(),
        agentClaims = emptyList(),
        latestByKind = emptyMap(),
        rejectedUnpairedExecutionCount = 0,
        errorCode = error
    )
}
