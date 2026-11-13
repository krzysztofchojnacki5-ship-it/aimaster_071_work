import pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator as Coordinator

private class FakeBackend(
    var status: String = "APPROVED",
    var ledgerSnapshot: String = "",
    var current: String = "after",
    var chat: String = "chat-1",
    var snapshotValid: Boolean = true,
    var snapshotBase: String = "before",
    var failAt: String = "",
    var rollbackResultTransform: ((Coordinator.Receipt) -> Coordinator.Receipt)? = null
) : Coordinator.Backend {
    val events = mutableListOf<String>()
    override fun recoverRequestedRollback(receipt: Coordinator.Receipt): Coordinator.Receipt {
        events += "recoverRollback"; if (failAt == "rollback") error("injected rollback failure")
        current = "before"
        return rollbackResultTransform?.invoke(receipt)
            ?: receipt.copy(rolledBack = true, rollbackRequested = false)
    }
    override fun verifySnapshot(snapshotId: String): Boolean { events += "verify"; return snapshotValid }
    override fun snapshotIdentity(snapshotId: String): String? { events += "snapshotIdentity"; return snapshotBase }
    override fun readLedger(changeSetId: String): Coordinator.Ledger {
        events += "readLedger"; if (failAt == "read") error("injected read failure")
        if (failAt == "corrupt") return Coordinator.Ledger(false, null)
        return Coordinator.Ledger(true, Coordinator.LedgerEntry(chat, status, ledgerSnapshot))
    }
    override fun currentWorkspaceIdentity(): String? { events += "currentWorkspaceIdentity"; return current }
    override fun recordSnapshot(changeSetId: String, snapshotId: String): Boolean {
        events += "recordSnapshot"; if (failAt == "snapshot") return false
        ledgerSnapshot = snapshotId; return true
    }
    override fun transition(changeSetId: String, nextStatus: String): Boolean {
        events += "transition:$nextStatus"; if (failAt == "transition") return false
        status = nextStatus; return true
    }
    override fun invalidateActiveScope(changeSetId: String): Boolean {
        events += "invalidate"; if (failAt == "invalidate") return false
        return true
    }
}

private fun receipt(rollback: Boolean = false) = Coordinator.Receipt(
    transactionId = "tx-1", changeSetId = "cs-1", chatId = "chat-1", snapshotId = "snap-1",
    beforeWorkspaceIdentity = "before", workspaceIdentity = "after", rolledBack = false,
    rollbackRequested = rollback
)

fun main() {
    run {
        val b = FakeBackend()
        val out = Coordinator.reconcile(receipt(), b)
        check(out.eligibleForActivation && !out.effectiveRolledBack)
        check(b.status == "APPLIED_UNVERIFIED" && b.ledgerSnapshot == "snap-1")
        println("PASS committed receipt attaches snapshot and advances status")
    }
    run {
        val b = FakeBackend(status = "APPLIED_UNVERIFIED", ledgerSnapshot = "snap-1")
        val out = Coordinator.reconcile(receipt(), b)
        check(out.eligibleForActivation && b.events.none { it.startsWith("transition") || it == "recordSnapshot" })
        println("PASS already reconciled receipt is idempotent")
    }
    run {
        val b = FakeBackend(failAt = "snapshot")
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check(b.status == "APPROVED")
        println("PASS snapshot persistence failure stops before status transition")
    }
    run {
        val b = FakeBackend(failAt = "transition")
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check(b.ledgerSnapshot == "snap-1" && b.status == "APPROVED")
        println("PASS status failure leaves durable snapshot for retry")
    }
    run {
        val b = FakeBackend(snapshotValid = false)
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check("readLedger" !in b.events)
        println("PASS corrupt snapshot blocks ledger mutation")
    }
    run {
        val b = FakeBackend(chat = "other-chat")
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check(b.events.none { it.startsWith("transition") })
        println("PASS chat mismatch fails closed")
    }
    run {
        val b = FakeBackend(current = "unrelated-workspace")
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check("readLedger" !in b.events && "verify" !in b.events && "snapshotIdentity" !in b.events)
        check(b.events.none { it.startsWith("recordSnapshot") || it.startsWith("transition") || it == "invalidate" })
        println("PASS unrelated workspace identity blocks rollback/snapshot/ledger access and mutation")
    }
    run {
        val b = FakeBackend(current = "unrelated-workspace")
        check(runCatching { Coordinator.reconcile(receipt(rollback = true), b) }.isFailure)
        check(b.events == listOf("currentWorkspaceIdentity"))
        println("PASS unrelated workspace blocks requested rollback before backend recovery")
    }
    run {
        val b = FakeBackend(status = "APPROVED", current = "before")
        val out = Coordinator.reconcile(receipt(), b)
        check(out.effectiveRolledBack && !out.eligibleForActivation && b.status == "REJECTED")
        check("invalidate" in b.events)
        println("PASS base workspace identity forces rollback reconciliation")
    }
    run {
        val b = FakeBackend()
        val out = Coordinator.reconcile(receipt(rollback = true), b)
        check(out.effectiveRolledBack && b.status == "REJECTED")
        check(b.events.indexOf("recoverRollback") < b.events.indexOf("verify"))
        println("PASS requested rollback is recovered before ledger reconciliation")
    }
    run {
        val b = FakeBackend(failAt = "invalidate", status = "APPLIED_UNVERIFIED", ledgerSnapshot = "snap-1", current = "before")
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check(b.status == "ROLLED_BACK")
        println("PASS failed scope invalidation preserves rollback status and fails closed")
    }
    run {
        val b = FakeBackend(failAt = "read")
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check(b.status == "APPROVED" && b.ledgerSnapshot.isBlank())
        check(b.events.none { it.startsWith("recordSnapshot") || it.startsWith("transition") || it == "invalidate" })
        println("PASS ledger read exception fails closed before any ledger mutation")
    }
    run {
        val b = FakeBackend(failAt = "corrupt")
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check(b.status == "APPROVED" && b.ledgerSnapshot.isBlank())
        check(b.events.none { it.startsWith("recordSnapshot") || it.startsWith("transition") || it == "invalidate" })
        println("PASS corrupt ledger fails closed without mutation")
    }
    run {
        val b = FakeBackend(failAt = "transition")
        check(runCatching { Coordinator.reconcile(receipt(), b) }.isFailure)
        check(b.ledgerSnapshot == "snap-1" && b.status == "APPROVED")
        b.failAt = ""
        val retry = Coordinator.reconcile(receipt(), b)
        check(retry.eligibleForActivation && b.status == "APPLIED_UNVERIFIED")
        check(b.events.count { it == "recordSnapshot" } == 1)
        println("PASS partial snapshot commit resumes on retry without duplicate snapshot write")
    }
    run {
        val b = FakeBackend(failAt = "rollback", current = "after")
        check(runCatching { Coordinator.reconcile(receipt(rollback = true), b) }.isFailure)
        check(b.current == "after" && b.status == "APPROVED")
        b.failAt = ""
        val retry = Coordinator.reconcile(receipt(rollback = true), b)
        check(retry.effectiveRolledBack && b.status == "REJECTED")
        println("PASS requested rollback failure remains retryable and converges on retry")
    }
    run {
        val b = FakeBackend(rollbackResultTransform = { it.copy(rolledBack = false, rollbackRequested = true) })
        check(runCatching { Coordinator.reconcile(receipt(rollback = true), b) }.isFailure)
        check(b.events == listOf("currentWorkspaceIdentity", "recoverRollback"))
        check(b.status == "APPROVED" && b.ledgerSnapshot.isBlank())
        println("PASS incomplete rollback acknowledgement blocks all reconciliation work")
    }
    run {
        val b = FakeBackend(rollbackResultTransform = { it.copy(transactionId = "foreign-tx", rolledBack = true, rollbackRequested = false) })
        check(runCatching { Coordinator.reconcile(receipt(rollback = true), b) }.isFailure)
        check(b.events == listOf("currentWorkspaceIdentity", "recoverRollback"))
        check(b.status == "APPROVED" && b.ledgerSnapshot.isBlank())
        println("PASS rollback receipt identity substitution is rejected before ledger access")
    }
    run {
        val b = FakeBackend()
        val invalid = receipt().copy(snapshotId = " ")
        check(runCatching { Coordinator.reconcile(invalid, b) }.isFailure)
        check(b.events.isEmpty())
        println("PASS incomplete durable receipt is rejected before backend I/O")
    }
    run {
        val b = FakeBackend()
        val contradictory = receipt().copy(rolledBack = true, rollbackRequested = true)
        check(runCatching { Coordinator.reconcile(contradictory, b) }.isFailure)
        check(b.events.isEmpty())
        println("PASS contradictory rollback flags are rejected before backend I/O")
    }
    println("PATCH_RECEIPT_COORDINATOR: 19/19 PASS")
}
