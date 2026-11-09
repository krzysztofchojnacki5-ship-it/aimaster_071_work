package pl.aimasterpro.app.core

/**
 * Converts exact host-scoped verification records into the nine MASTER completion gates.
 * Agent claims and missing evidence never become PASS. The evaluator is only invoked for an
 * explicitly selected, host-owned ChangeSet; ordinary chat is not forced through a code gate.
 */
object MasterQualityGateCoordinator {
    data class Input(
        val selected: MasterEvidenceContextBuilder.Candidate,
        val evidenceRows: List<VerificationEvidence>,
        val evidenceLedgerValid: Boolean,
        val safetyValidated: Boolean,
        val persistenceVerified: Boolean,
        val independentReviewCompleted: Boolean,
        val responsePresent: Boolean,
        val responseCheckPassed: Boolean
    )

    fun evaluate(input: Input): QualityGateEvaluator.Evaluation {
        val scope = input.selected
        require(scope.changeSetId.isNotBlank() && scope.taskId.isNotBlank() &&
            scope.chatId.isNotBlank() && scope.snapshotId.isNotBlank()) {
            "Quality Gate requires an exact host-owned ChangeSet scope."
        }
        require(input.evidenceRows.all {
            it.taskId == scope.taskId && it.chatId == scope.chatId && it.snapshotId == scope.snapshotId
        }) { "Quality Gate cannot combine evidence from another task, chat or snapshot." }

        val latest = if (input.evidenceLedgerValid) {
            input.evidenceRows.groupBy { it.kind }.mapValues { (_, rows) ->
                rows.maxWithOrNull(compareBy<VerificationEvidence> { it.observedAtEpochMs }.thenBy { it.id })!!
            }
        } else emptyMap()

        fun evidenceResult(kind: VerificationEvidenceKind): QualityGateEvaluator.GateResult {
            val row = latest[kind]
            val outcome = when {
                !input.evidenceLedgerValid -> QualityGateEvaluator.Outcome.NOT_RUN
                row == null -> QualityGateEvaluator.Outcome.NOT_RUN
                row.status == VerificationEvidenceStatus.PASS -> QualityGateEvaluator.Outcome.PASS
                row.status == VerificationEvidenceStatus.FAIL -> QualityGateEvaluator.Outcome.FAIL
                row.status == VerificationEvidenceStatus.CLAIM_ONLY -> QualityGateEvaluator.Outcome.INCONCLUSIVE
                else -> QualityGateEvaluator.Outcome.NOT_RUN
            }
            return QualityGateEvaluator.GateResult(
                gate = when (kind) {
                    VerificationEvidenceKind.PATCH_SCOPE -> QualityGateEvaluator.Gate.SCOPE
                    VerificationEvidenceKind.PROJECT_TESTS -> QualityGateEvaluator.Gate.CORRECTNESS
                    VerificationEvidenceKind.UNIT_TESTS -> QualityGateEvaluator.Gate.REGRESSION
                    VerificationEvidenceKind.ANDROID_BUILD -> QualityGateEvaluator.Gate.BUILD
                    else -> error("Unsupported direct gate mapping: $kind")
                },
                outcome = outcome,
                evidenceRef = if (outcome == QualityGateEvaluator.Outcome.PASS) "verification:${row!!.id}" else "",
                detail = row?.summary ?: "Brak dokładnego dowodu dla $kind."
            )
        }

        val integrationRows = listOfNotNull(
            latest[VerificationEvidenceKind.INTEGRATION_TESTS],
            latest[VerificationEvidenceKind.DEVICE_TEST]
        )
        val integrationOutcome = when {
            !input.evidenceLedgerValid || integrationRows.isEmpty() -> QualityGateEvaluator.Outcome.NOT_RUN
            integrationRows.any { it.status == VerificationEvidenceStatus.FAIL } -> QualityGateEvaluator.Outcome.FAIL
            integrationRows.any { it.status == VerificationEvidenceStatus.PASS } -> QualityGateEvaluator.Outcome.PASS
            integrationRows.any { it.status == VerificationEvidenceStatus.CLAIM_ONLY } -> QualityGateEvaluator.Outcome.INCONCLUSIVE
            else -> QualityGateEvaluator.Outcome.NOT_RUN
        }
        val integrationEvidence = integrationRows.firstOrNull { it.status == VerificationEvidenceStatus.PASS }

        val results = listOf(
            evidenceResult(VerificationEvidenceKind.PATCH_SCOPE),
            QualityGateEvaluator.GateResult(
                gate = QualityGateEvaluator.Gate.SAFETY,
                outcome = if (input.safetyValidated) QualityGateEvaluator.Outcome.PASS else QualityGateEvaluator.Outcome.BLOCKED,
                evidenceRef = if (input.safetyValidated) "changeset:${scope.changeSetId}:ChangeSafetyGuard" else "",
                detail = if (input.safetyValidated) "ChangeSet został przyjęty przez ChangeSafetyGuard przed aktywacją."
                    else "Brak potwierdzenia walidacji bezpieczeństwa ChangeSafetyGuard."
            ),
            evidenceResult(VerificationEvidenceKind.PROJECT_TESTS),
            evidenceResult(VerificationEvidenceKind.UNIT_TESTS),
            evidenceResult(VerificationEvidenceKind.ANDROID_BUILD),
            QualityGateEvaluator.GateResult(
                gate = QualityGateEvaluator.Gate.INTEGRATION,
                outcome = integrationOutcome,
                evidenceRef = if (integrationOutcome == QualityGateEvaluator.Outcome.PASS) "verification:${integrationEvidence!!.id}" else "",
                detail = integrationEvidence?.summary ?: "Brak wyniku testu integracyjnego lub urządzeniowego."
            ),
            QualityGateEvaluator.GateResult(
                gate = QualityGateEvaluator.Gate.PERSISTENCE,
                outcome = if (input.persistenceVerified) QualityGateEvaluator.Outcome.PASS else QualityGateEvaluator.Outcome.BLOCKED,
                evidenceRef = if (input.persistenceVerified) "task:${scope.taskId}:durable-state" else "",
                detail = if (input.persistenceVerified) "Rejestry zadania, decyzji, błędów i dowodów są czytelne."
                    else "Nie potwierdzono spójnego, trwałego zapisu rejestrów zadania i dowodów."
            ),
            QualityGateEvaluator.GateResult(
                gate = QualityGateEvaluator.Gate.REVIEW,
                outcome = if (input.independentReviewCompleted) QualityGateEvaluator.Outcome.PASS else QualityGateEvaluator.Outcome.NOT_RUN,
                evidenceRef = if (input.independentReviewCompleted) "task:${scope.taskId}:independent-review" else "",
                detail = if (input.independentReviewCompleted) "Niezależna recenzja kodu została potwierdzona."
                    else "Brak niezależnego dowodu recenzji kodu; sama recenzja raportów agentów nie wystarcza."
            ),
            QualityGateEvaluator.GateResult(
                gate = QualityGateEvaluator.Gate.REPORT,
                outcome = when {
                    !input.responsePresent -> QualityGateEvaluator.Outcome.FAIL
                    input.responseCheckPassed -> QualityGateEvaluator.Outcome.PASS
                    else -> QualityGateEvaluator.Outcome.INCONCLUSIVE
                },
                evidenceRef = if (input.responsePresent && input.responseCheckPassed) "task:${scope.taskId}:master-response-check" else "",
                detail = when {
                    !input.responsePresent -> "Brak końcowej odpowiedzi MASTER-a."
                    input.responseCheckPassed -> "Końcowa odpowiedź przeszła lokalną kontrolę strukturalną."
                    else -> "Kontrola odpowiedzi wykryła ostrzeżenia; raport wymaga jawnego zastrzeżenia."
                }
            )
        )
        return QualityGateEvaluator.evaluate(results)
    }
}
