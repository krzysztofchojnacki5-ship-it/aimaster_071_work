package pl.aimasterpro.app.core

/**
 * Central authorization policy for persisted learning-memory mutations.
 *
 * This policy is deliberately side-effect free. Callers must obtain an allow decision
 * before invoking a store mutation; the next migration step routes every mutator through
 * the governed adapter and records the decision in the durable audit journal.
 */
object MemoryGovernance {
    enum class Actor {
        USER,
        MASTER,
        AGENT,
        SYSTEM_RECOVERY
    }

    enum class Mutation {
        ADD_SHARED_RULE,
        REMOVE_SHARED_RULE,
        ADD_AGENT_LESSON,
        REMOVE_AGENT_LESSON,
        APPROVE_PROPOSAL,
        REJECT_PROPOSAL,
        RECONCILE_APPROVED_PROPOSAL,
        RECONCILE_REVOKED_PROPOSAL
    }

    data class Request(
        val actor: Actor,
        val mutation: Mutation,
        val objectId: String,
        val reason: String,
        val approvalRef: String = ""
    )

    data class Decision(
        val allowed: Boolean,
        val code: String,
        val message: String
    )

    private val userApprovedMutations = setOf(
        Mutation.ADD_SHARED_RULE,
        Mutation.REMOVE_SHARED_RULE,
        Mutation.ADD_AGENT_LESSON,
        Mutation.REMOVE_AGENT_LESSON,
        Mutation.APPROVE_PROPOSAL,
        Mutation.REJECT_PROPOSAL
    )

    private val recoveryMutations = setOf(
        Mutation.RECONCILE_APPROVED_PROPOSAL,
        Mutation.RECONCILE_REVOKED_PROPOSAL
    )

    fun authorize(request: Request): Decision {
        if (request.objectId.isBlank() || request.objectId.length > MAX_FIELD_CHARS) {
            return deny("INVALID_OBJECT", "Mutacja pamięci wymaga poprawnego identyfikatora obiektu.")
        }
        if (request.reason.isBlank() || request.reason.length > MAX_REASON_CHARS) {
            return deny("INVALID_REASON", "Mutacja pamięci wymaga krótkiego, jawnego powodu.")
        }
        if (request.approvalRef.length > MAX_FIELD_CHARS) {
            return deny("INVALID_APPROVAL_REF", "Identyfikator zatwierdzenia przekracza limit długości.")
        }

        return when (request.actor) {
            Actor.AGENT -> deny(
                "AGENT_READ_ONLY",
                "Agenci nie mogą samodzielnie zmieniać trwałej pamięci ani statusów zatwierdzeń."
            )
            Actor.MASTER -> deny(
                "MASTER_PROPOSAL_ONLY",
                "MASTER może przygotować propozycję uczenia, ale nie może sam jej zatwierdzić ani zmienić pamięci."
            )
            Actor.USER -> {
                if (request.mutation !in userApprovedMutations) {
                    deny("USER_MUTATION_SCOPE", "Ta operacja nie należy do zakresu ręcznego zatwierdzenia użytkownika.")
                } else if (request.approvalRef.isBlank()) {
                    deny("USER_APPROVAL_REQUIRED", "Operacja wymaga identyfikatora jawnego zatwierdzenia użytkownika.")
                } else {
                    allow("USER_APPROVED", "Operacja mieści się w zakresie jawnie zatwierdzonej zmiany użytkownika.")
                }
            }
            Actor.SYSTEM_RECOVERY -> {
                val expectedRef = "proposal:${request.objectId}"
                if (request.mutation !in recoveryMutations) {
                    deny("RECOVERY_SCOPE", "Odzyskiwanie może wyłącznie uzgadniać pamięć z trwałym rejestrem propozycji.")
                } else if (request.approvalRef != expectedRef) {
                    deny("RECOVERY_REFERENCE_REQUIRED", "Odzyskiwanie wymaga dokładnego odwołania do propozycji źródłowej.")
                } else {
                    allow("RECOVERY_RECONCILIATION", "Operacja jest ograniczona do odtworzenia stanu z rejestru propozycji.")
                }
            }
        }
    }

    private fun allow(code: String, message: String) = Decision(true, code, message)
    private fun deny(code: String, message: String) = Decision(false, code, message)

    private const val MAX_FIELD_CHARS = 200
    private const val MAX_REASON_CHARS = 500
}
