package pl.aimasterpro.app.core

/**
 * Deterministic quality-gate policy for MASTER task finalization.
 *
 * Missing evidence is NOT RUN, never PASS. A WAIVED gate is accepted only when explicit
 * user approval and a non-empty residual-risk record are both present.
 */
object QualityGateEvaluator {
    enum class Gate {
        SCOPE, SAFETY, CORRECTNESS, REGRESSION, BUILD,
        INTEGRATION, PERSISTENCE, REVIEW, REPORT
    }

    enum class Outcome { PASS, FAIL, BLOCKED, NOT_RUN, INCONCLUSIVE, WAIVED }

    data class GateResult(
        val gate: Gate,
        val outcome: Outcome,
        val evidenceRef: String = "",
        val detail: String = "",
        val userApprovedWaiver: Boolean = false,
        val residualRisk: String = ""
    )

    data class Evaluation(
        val overall: Outcome,
        val completionAllowed: Boolean,
        val results: List<GateResult>,
        val missingGates: List<Gate>
    )

    fun evaluate(results: List<GateResult>): Evaluation {
        val duplicateGates = results.groupBy { it.gate }.filterValues { it.size > 1 }.keys
        require(duplicateGates.isEmpty()) { "Duplicate quality gate results: $duplicateGates" }

        val byGate = results.associateBy { it.gate }
        val missing = Gate.entries.filterNot(byGate::containsKey)
        val normalized = Gate.entries.map { gate ->
            val result = byGate[gate] ?: GateResult(
                gate = gate,
                outcome = Outcome.NOT_RUN,
                detail = "Brak zapisanego wyniku bramki."
            )
            when {
                result.outcome == Outcome.WAIVED &&
                    (!result.userApprovedWaiver || result.residualRisk.isBlank()) ->
                    result.copy(
                        outcome = Outcome.BLOCKED,
                        detail = "Odstępstwo wymaga jawnej zgody użytkownika i opisu ryzyka."
                    )
                result.outcome == Outcome.PASS && result.evidenceRef.isBlank() ->
                    result.copy(
                        outcome = Outcome.INCONCLUSIVE,
                        detail = "PASS bez referencji do dowodu nie jest akceptowany."
                    )
                else -> result
            }
        }

        val outcomes = normalized.map { it.outcome }
        val overall = when {
            Outcome.FAIL in outcomes -> Outcome.FAIL
            Outcome.BLOCKED in outcomes -> Outcome.BLOCKED
            Outcome.NOT_RUN in outcomes -> Outcome.NOT_RUN
            Outcome.INCONCLUSIVE in outcomes -> Outcome.INCONCLUSIVE
            Outcome.WAIVED in outcomes -> Outcome.WAIVED
            else -> Outcome.PASS
        }
        val completionAllowed = normalized.all {
            it.outcome == Outcome.PASS || it.outcome == Outcome.WAIVED
        }
        return Evaluation(overall, completionAllowed, normalized, missing)
    }
}
