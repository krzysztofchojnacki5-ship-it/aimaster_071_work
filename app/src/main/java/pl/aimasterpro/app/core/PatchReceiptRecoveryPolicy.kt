package pl.aimasterpro.app.core

/** Pure decision layer for reconciling a durable workspace receipt with its ChangeSet ledger row. */
object PatchReceiptRecoveryPolicy {
    data class Input(
        val receiptRolledBack: Boolean,
        val currentWorkspaceIdentity: String,
        val beforeWorkspaceIdentity: String,
        val changeStatus: String,
        val ledgerSnapshotId: String
    )

    data class Decision(
        val effectiveRolledBack: Boolean,
        val attachSnapshot: Boolean,
        val transitionTo: String?,
        val invalidateActiveScope: Boolean,
        val eligibleForActivation: Boolean
    )

    fun decide(input: Input): Decision {
        require(input.currentWorkspaceIdentity.isNotBlank()) { "RECOVERY_CURRENT_IDENTITY_MISSING" }
        require(input.beforeWorkspaceIdentity.isNotBlank()) { "RECOVERY_BASE_IDENTITY_MISSING" }
        require(input.changeStatus in STATUSES) { "RECOVERY_CHANGE_STATUS_INVALID" }

        val rolledBack = input.receiptRolledBack ||
            input.currentWorkspaceIdentity == input.beforeWorkspaceIdentity ||
            input.changeStatus == "ROLLED_BACK" || input.changeStatus == "REJECTED"
        val attachSnapshot = input.changeStatus in setOf("APPROVED", "APPLIED_UNVERIFIED") && input.ledgerSnapshotId.isBlank()
        val transition = if (rolledBack) {
            when (input.changeStatus) {
                "APPROVED" -> "REJECTED"
                "APPLIED_UNVERIFIED", "APPLIED" -> "ROLLED_BACK"
                else -> null
            }
        } else {
            when (input.changeStatus) {
                "APPROVED" -> "APPLIED_UNVERIFIED"
                "APPLIED_UNVERIFIED", "APPLIED" -> null
                else -> error("RECOVERY_NON_ROLLBACK_STATUS_INVALID")
            }
        }
        return Decision(
            effectiveRolledBack = rolledBack,
            attachSnapshot = attachSnapshot,
            transitionTo = transition,
            invalidateActiveScope = rolledBack,
            eligibleForActivation = !rolledBack && input.changeStatus in setOf("APPROVED", "APPLIED_UNVERIFIED", "APPLIED")
        )
    }

    private val STATUSES = setOf("PROPOSED", "APPROVED", "APPLIED_UNVERIFIED", "APPLIED", "ROLLED_BACK", "REJECTED")
}
