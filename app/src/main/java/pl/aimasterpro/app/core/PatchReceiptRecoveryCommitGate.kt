package pl.aimasterpro.app.core

/** Keeps receipt acknowledgement in the same workspace critical section as pointer activation. */
object PatchReceiptRecoveryCommitGate {
    enum class Result { ACKNOWLEDGED, ACKNOWLEDGE_FAILED, ACTIVATION_FAILED, INVALIDATION_FAILED }

    fun commit(
        eligibleForActivation: Boolean,
        expectedWorkspaceIdentity: String,
        chatStillMatches: () -> Boolean,
        currentWorkspaceIdentity: () -> String,
        activate: () -> Boolean,
        invalidateActiveScope: () -> Unit,
        acknowledge: () -> Boolean
    ): Result = ProjectWorkspaceOperationLock.withLock {
        if (eligibleForActivation) {
            val scopeMatches = expectedWorkspaceIdentity.isNotBlank() &&
                chatStillMatches() && currentWorkspaceIdentity() == expectedWorkspaceIdentity
            if (!scopeMatches) {
                // Reconciliation may have completed just before a workspace restore or chat
                // switch. Never leave an older active pointer behind when this receipt is stale.
                if (runCatching { invalidateActiveScope() }.isFailure) return@withLock Result.INVALIDATION_FAILED
            } else if (!activate()) {
                // Preserve the receipt only when the original scope is still current; a concurrent
                // chat/workspace change makes activation ineligible rather than retryable.
                if (chatStillMatches() && currentWorkspaceIdentity() == expectedWorkspaceIdentity) {
                    return@withLock Result.ACTIVATION_FAILED
                }
                if (runCatching { invalidateActiveScope() }.isFailure) return@withLock Result.INVALIDATION_FAILED
            } else if (!chatStillMatches() || currentWorkspaceIdentity() != expectedWorkspaceIdentity) {
                // Workspace imports are excluded by the lock. Chat selection is UI-owned and may
                // still change; remove a pointer if that happened during activation.
                if (runCatching { invalidateActiveScope() }.isFailure) return@withLock Result.INVALIDATION_FAILED
            }
        }
        if (acknowledge()) Result.ACKNOWLEDGED else Result.ACKNOWLEDGE_FAILED
    }
}
