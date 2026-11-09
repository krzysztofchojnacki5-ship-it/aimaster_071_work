package pl.aimasterpro.app.core

import java.util.UUID

/** Evidence origin is explicit so an agent's statement can never masquerade as an executed test. */
enum class VerificationEvidenceOrigin {
    RUNTIME_CHECK,
    TOOL_EXECUTION,
    STATIC_REVIEW,
    AGENT_CLAIM,
    NOT_RUN
}

enum class VerificationEvidenceStatus {
    PASS,
    FAIL,
    NOT_RUN,
    CLAIM_ONLY
}

enum class VerificationEvidenceKind {
    WORKSPACE_INTEGRITY,
    SNAPSHOT_INTEGRITY,
    PATCH_SCOPE,
    PROJECT_TESTS,
    COMPILATION,
    UNIT_TESTS,
    INTEGRATION_TESTS,
    ROBOLECTRIC,
    ANDROID_BUILD,
    DEVICE_TEST,
    OTHER,
    CODE_REVIEW
}

data class VerificationEvidence(
    val id: String,
    val taskId: String,
    val kind: VerificationEvidenceKind,
    val status: VerificationEvidenceStatus,
    val origin: VerificationEvidenceOrigin,
    val summary: String,
    val observedAtEpochMs: Long,
    /** Required for tool executions; identifies the host-recorded invocation, not model text. */
    val executionId: String = "",
    /** Conversation that owns the work; blank only for legacy/unscoped records. */
    val chatId: String = "",
    /** Exact workspace snapshot observed by this evidence; blank for non-workspace evidence. */
    val snapshotId: String = ""
) {
    init {
        require(id.isNotBlank()) { "Identyfikator dowodu nie może być pusty." }
        require(taskId.isNotBlank()) { "Dowód musi być przypisany do zadania." }
        require(summary.isNotBlank()) { "Dowód musi zawierać opis." }
        require(observedAtEpochMs >= 0L) { "Nieprawidłowy czas dowodu." }
        when (origin) {
            VerificationEvidenceOrigin.AGENT_CLAIM -> require(status == VerificationEvidenceStatus.CLAIM_ONLY) {
                "Deklaracja agenta nie może być oznaczona jako PASS/FAIL testu."
            }
            VerificationEvidenceOrigin.NOT_RUN -> require(status == VerificationEvidenceStatus.NOT_RUN) {
                "Źródło NOT_RUN może utworzyć wyłącznie dowód NOT_RUN."
            }
            VerificationEvidenceOrigin.TOOL_EXECUTION -> {
                require(executionId.isNotBlank()) { "Wynik narzędzia wymaga identyfikatora rzeczywistego wywołania." }
                require(status != VerificationEvidenceStatus.CLAIM_ONLY) {
                    "Wynik narzędzia nie może mieć statusu CLAIM_ONLY."
                }
            }
            VerificationEvidenceOrigin.RUNTIME_CHECK,
            VerificationEvidenceOrigin.STATIC_REVIEW -> require(status != VerificationEvidenceStatus.CLAIM_ONLY) {
                "Kontrola runtime/statyczna nie może być deklaracją agenta."
            }
        }
    }

    companion object {
        fun runtimeCheck(
            taskId: String,
            kind: VerificationEvidenceKind,
            status: VerificationEvidenceStatus,
            summary: String,
            observedAtEpochMs: Long = System.currentTimeMillis(),
            chatId: String = "",
            snapshotId: String = ""
        ) = VerificationEvidence(
            UUID.randomUUID().toString(), taskId, kind, status,
            if (status == VerificationEvidenceStatus.NOT_RUN) VerificationEvidenceOrigin.NOT_RUN
            else VerificationEvidenceOrigin.RUNTIME_CHECK,
            summary, observedAtEpochMs, chatId = chatId, snapshotId = snapshotId
        )

        fun toolExecution(
            taskId: String,
            kind: VerificationEvidenceKind,
            status: VerificationEvidenceStatus,
            summary: String,
            executionId: String,
            observedAtEpochMs: Long = System.currentTimeMillis(),
            chatId: String = "",
            snapshotId: String = ""
        ) = VerificationEvidence(
            UUID.randomUUID().toString(), taskId, kind, status,
            VerificationEvidenceOrigin.TOOL_EXECUTION, summary, observedAtEpochMs, executionId, chatId, snapshotId
        )

        /** Agent-authored statements are retained as claims, never promoted to verification. */
        fun agentClaim(
            taskId: String,
            kind: VerificationEvidenceKind,
            summary: String,
            observedAtEpochMs: Long = System.currentTimeMillis(),
            chatId: String = "",
            snapshotId: String = ""
        ) = VerificationEvidence(
            UUID.randomUUID().toString(), taskId, kind, VerificationEvidenceStatus.CLAIM_ONLY,
            VerificationEvidenceOrigin.AGENT_CLAIM, summary, observedAtEpochMs, chatId = chatId, snapshotId = snapshotId
        )

        fun notRun(
            taskId: String,
            kind: VerificationEvidenceKind,
            summary: String,
            observedAtEpochMs: Long = System.currentTimeMillis(),
            chatId: String = "",
            snapshotId: String = "",
            executionId: String = ""
        ) = VerificationEvidence(
            UUID.randomUUID().toString(), taskId, kind, VerificationEvidenceStatus.NOT_RUN,
            VerificationEvidenceOrigin.NOT_RUN, summary, observedAtEpochMs,
            executionId = executionId, chatId = chatId, snapshotId = snapshotId
        )
    }
}

enum class VerificationAssessmentStatus { PASS, FAIL, INCOMPLETE, NOT_RUN }

data class VerificationAssessment(
    val status: VerificationAssessmentStatus,
    val requiredKinds: Set<VerificationEvidenceKind>,
    val missingKinds: Set<VerificationEvidenceKind>,
    val unresolvedKinds: Set<VerificationEvidenceKind>,
    val failedKinds: Set<VerificationEvidenceKind>
) {
    companion object {
        /** Latest evidence per kind wins; claims and NOT_RUN never satisfy a required check. */
        fun assess(
            evidence: List<VerificationEvidence>,
            requiredKinds: Set<VerificationEvidenceKind>
        ): VerificationAssessment {
            require(requiredKinds.isNotEmpty()) { "Wymagany jest co najmniej jeden rodzaj dowodu." }
            require(evidence.map { it.taskId }.distinct().size <= 1) {
                "Nie wolno łączyć dowodów z różnych zadań w jednej ocenie."
            }
            require(evidence.map { it.chatId }.distinct().size <= 1) {
                "Nie wolno łączyć dowodów z różnych czatów w jednej ocenie."
            }
            require(evidence.map { it.snapshotId }.distinct().size <= 1) {
                "Nie wolno łączyć dowodów z różnych snapshotów w jednej ocenie."
            }
            val latest = evidence.groupBy { it.kind }.mapValues { (_, rows) ->
                rows.maxWithOrNull(compareBy<VerificationEvidence> { it.observedAtEpochMs }.thenBy { it.id })!!
            }
            val missing = requiredKinds.filterTo(linkedSetOf()) { it !in latest }
            val failed = requiredKinds.filterTo(linkedSetOf()) {
                latest[it]?.status == VerificationEvidenceStatus.FAIL
            }
            val unresolved = requiredKinds.filterTo(linkedSetOf()) { kind ->
                val row = latest[kind]
                row != null && row.status != VerificationEvidenceStatus.PASS && row.status != VerificationEvidenceStatus.FAIL
            }
            val status = when {
                failed.isNotEmpty() -> VerificationAssessmentStatus.FAIL
                missing.isEmpty() && unresolved.isEmpty() -> VerificationAssessmentStatus.PASS
                evidence.isEmpty() -> VerificationAssessmentStatus.NOT_RUN
                else -> VerificationAssessmentStatus.INCOMPLETE
            }
            return VerificationAssessment(status, requiredKinds.toSet(), missing, unresolved, failed)
        }
    }
}
