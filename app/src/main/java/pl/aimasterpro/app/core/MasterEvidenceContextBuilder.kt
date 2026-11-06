package pl.aimasterpro.app.core

/**
 * Selects evidence for MASTER only when the user prompt explicitly identifies one change set,
 * task id, or snapshot id in the active chat. No "latest item" fallback is permitted.
 */
object MasterEvidenceContextBuilder {
    data class Candidate(
        val changeSetId: String,
        val taskId: String,
        val chatId: String,
        val snapshotId: String,
        val updatedAt: Long
    )

    enum class Status { READY, NO_EXPLICIT_REFERENCE, AMBIGUOUS_REFERENCE, INVALID_CANDIDATE, LEDGER_INVALID }

    data class Result(
        val status: Status,
        val selected: Candidate?,
        val assessment: VerificationAssessment?,
        val evidenceCount: Int,
        val agentClaimCount: Int,
        val rejectedUnpairedExecutionCount: Int,
        val contextText: String
    )

    fun build(
        prompt: String,
        activeChatId: String,
        candidates: List<Candidate>,
        resolver: VerificationEvidenceResolver,
        requiredKinds: Set<VerificationEvidenceKind> = DEFAULT_REQUIRED_KINDS
    ): Result {
        if (prompt.isBlank() || activeChatId.isBlank()) return unavailable(Status.NO_EXPLICIT_REFERENCE)
        val eligible = candidates.filter {
            it.chatId == activeChatId && it.taskId.isNotBlank() && it.snapshotId.isNotBlank() && it.changeSetId.isNotBlank()
        }
        val invalidInChat = candidates.any { it.chatId == activeChatId &&
            (it.taskId.isBlank() || it.snapshotId.isBlank() || it.changeSetId.isBlank()) }
        val lowerPrompt = prompt.lowercase()
        val matched = eligible.filter { candidate ->
            listOf(candidate.changeSetId, candidate.taskId, candidate.snapshotId)
                .any { token -> token.length >= MIN_REFERENCE_LENGTH && lowerPrompt.contains(token.lowercase()) }
        }.distinctBy { it.changeSetId }
        if (matched.isEmpty()) return unavailable(if (invalidInChat) Status.INVALID_CANDIDATE else Status.NO_EXPLICIT_REFERENCE)
        if (matched.size != 1) return unavailable(Status.AMBIGUOUS_REFERENCE)

        return buildSelected(matched.single(), resolver, requiredKinds)
    }

    /** Uses a scope selected by the host/orchestrator; no user-text parsing or latest-item fallback. */
    fun buildForHostScope(
        selected: Candidate?,
        activeChatId: String,
        resolver: VerificationEvidenceResolver,
        requiredKinds: Set<VerificationEvidenceKind> = DEFAULT_REQUIRED_KINDS
    ): Result {
        if (activeChatId.isBlank() || selected == null) return unavailable(Status.NO_EXPLICIT_REFERENCE)
        if (selected.chatId != activeChatId || selected.taskId.isBlank() || selected.snapshotId.isBlank() || selected.changeSetId.isBlank()) {
            return unavailable(Status.INVALID_CANDIDATE)
        }
        return buildSelected(selected, resolver, requiredKinds)
    }

    private fun buildSelected(
        selected: Candidate,
        resolver: VerificationEvidenceResolver,
        requiredKinds: Set<VerificationEvidenceKind>
    ): Result {
        val bundle = resolver.resolve(selected.taskId, selected.chatId, selected.snapshotId, requiredKinds)
        if (bundle.status != VerificationEvidenceResolver.ResolutionStatus.READY || bundle.assessment == null) {
            return Result(Status.LEDGER_INVALID, selected, null, 0, 0,
                bundle.rejectedUnpairedExecutionCount,
                "DOWODY WERYFIKACJI: NIEDOSTĘPNE (${bundle.errorCode.ifBlank { bundle.status.name }}). Nie wolno twierdzić, że testy, kompilacja ani build zostały potwierdzone.")
        }
        val assessment = bundle.assessment
        val lines = buildList {
            add("DOWODY WERYFIKACJI — zakres dokładny")
            add("changeSetId=${selected.changeSetId}; taskId=${selected.taskId}; chatId=${selected.chatId}; snapshotId=${selected.snapshotId}")
            add("assessment=${assessment.status}")
            add("required=${assessment.requiredKinds.sortedBy { it.name }.joinToString()}")
            add("missing=${assessment.missingKinds.sortedBy { it.name }.joinToString().ifBlank { "brak" }}")
            add("unresolved=${assessment.unresolvedKinds.sortedBy { it.name }.joinToString().ifBlank { "brak" }}")
            add("failed=${assessment.failedKinds.sortedBy { it.name }.joinToString().ifBlank { "brak" }}")
            bundle.verifiedEvidence.forEach { add("${it.kind}=${it.status} (${it.origin}; ${it.summary.take(220)})") }
            add("agentClaims=${bundle.agentClaims.size} (informacyjne; nigdy nie spełniają wymagań)")
            add("rejectedUnpairedExecution=${bundle.rejectedUnpairedExecutionCount}")
            add("Zasada: PASS oceny oznacza wyłącznie komplet wymaganych rodzajów dowodów w tym zakresie. Nie rozszerzaj tego wniosku na inne zadania ani build APK, jeśli ANDROID_BUILD nie jest wymagany i potwierdzony.")
        }.joinToString("\n")
        return Result(Status.READY, selected, assessment, bundle.verifiedEvidence.size,
            bundle.agentClaims.size, bundle.rejectedUnpairedExecutionCount, lines)
    }

    private fun unavailable(status: Status) = Result(
        status, null, null, 0, 0, 0,
        "DOWODY WERYFIKACJI: brak jednoznacznego, zweryfikowanego zakresu hosta dla aktywnego czatu. Nie pobrano dowodów i nie wolno deklarować potwierdzonego wykonania testów lub builda. Nie wybieraj automatycznie najnowszego wpisu."
    )

    private const val MIN_REFERENCE_LENGTH = 8
    val DEFAULT_REQUIRED_KINDS = setOf(
        VerificationEvidenceKind.WORKSPACE_INTEGRITY,
        VerificationEvidenceKind.SNAPSHOT_INTEGRITY,
        VerificationEvidenceKind.PATCH_SCOPE,
        VerificationEvidenceKind.PROJECT_TESTS
    )
}
