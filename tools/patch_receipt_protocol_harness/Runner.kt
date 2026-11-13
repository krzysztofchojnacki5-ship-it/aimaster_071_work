import pl.aimasterpro.app.core.PatchReceiptRecoveryPolicy

/** Host-JVM model of the durable write order used by MainActivity startup reconciliation. */
private data class DurableState(
    var receiptPending: Boolean = true,
    var receiptRolledBack: Boolean = false,
    var currentIdentity: String = "after",
    val baseIdentity: String = "before",
    val committedIdentity: String = "after",
    var changeStatus: String = "APPROVED",
    var snapshotId: String = "",
    var activePointer: String = "old",
    var activeChat: String = "chat-1",
    val receiptChat: String = "chat-1",
    val changeSetId: String = "cs-1"
)

private class InjectedCrash(val boundary: String) : RuntimeException("CRASH_AT_$boundary")

private fun recover(state: DurableState, crashAfter: String? = null, selectedChat: String = state.receiptChat) {
    if (!state.receiptPending) return
    val decision = PatchReceiptRecoveryPolicy.decide(
        PatchReceiptRecoveryPolicy.Input(
            receiptRolledBack = state.receiptRolledBack,
            currentWorkspaceIdentity = state.currentIdentity,
            beforeWorkspaceIdentity = state.baseIdentity,
            changeStatus = state.changeStatus,
            ledgerSnapshotId = state.snapshotId
        )
    )
    if (decision.attachSnapshot) state.snapshotId = "snapshot-1"
    if (crashAfter == "snapshot") throw InjectedCrash("snapshot")

    decision.transitionTo?.let { state.changeStatus = it }
    if (crashAfter == "status") throw InjectedCrash("status")

    if (decision.invalidateActiveScope) state.activePointer = ""
    if (crashAfter == "invalidate") throw InjectedCrash("invalidate")

    val workspaceStillMatches = state.currentIdentity == state.committedIdentity
    if (decision.eligibleForActivation && workspaceStillMatches && selectedChat == state.receiptChat) {
        state.activePointer = state.changeSetId
    }
    if (crashAfter == "pointer") throw InjectedCrash("pointer")

    // The production flow acknowledges only after all required durable reconciliation steps.
    state.receiptPending = false
}

private fun expectCrashAt(boundary: String, state: DurableState) {
    val failure = runCatching { recover(state, crashAfter = boundary) }.exceptionOrNull()
    check(failure is InjectedCrash && failure.boundary == boundary) { "Expected injected crash at $boundary, got $failure" }
    recover(state)
    check(!state.receiptPending) { "Receipt should be acknowledged after retry at $boundary" }
}

fun main() {
    listOf("snapshot", "status", "invalidate", "pointer").forEach { boundary ->
        val state = DurableState()
        expectCrashAt(boundary, state)
        check(state.changeStatus == "APPLIED_UNVERIFIED")
        check(state.snapshotId == "snapshot-1")
        check(state.activePointer == "cs-1")
        println("PASS committed recovery resumes after $boundary")
    }

    run {
        val state = DurableState(receiptRolledBack = true, currentIdentity = "before")
        expectCrashAt("status", state)
        check(state.changeStatus == "REJECTED")
        check(state.activePointer.isEmpty())
        println("PASS rollback recovery resumes after ledger transition")
    }

    run {
        val state = DurableState(currentIdentity = "later-import")
        recover(state)
        check(!state.receiptPending)
        check(state.activePointer == "old")
        check(state.changeStatus == "APPLIED_UNVERIFIED")
        println("PASS later workspace identity is never activated")
    }

    run {
        val state = DurableState()
        recover(state, selectedChat = "chat-2")
        check(!state.receiptPending)
        check(state.activePointer == "old")
        check(state.changeStatus == "APPLIED_UNVERIFIED")
        println("PASS chat switch reconciles ledger without cross-chat activation")
    }

    run {
        val state = DurableState()
        recover(state)
        val stable = state.copy()
        recover(state)
        check(state == stable) { "Repeated recovery after acknowledgement must be a no-op" }
        println("PASS acknowledged recovery is idempotent")
    }

    println("PATCH_RECEIPT_PROTOCOL: 8/8 PASS")
}
