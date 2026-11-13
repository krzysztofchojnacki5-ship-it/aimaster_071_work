package pl.aimasterpro.app.core

/** Production orchestration for reconciling a durable workspace receipt with the host ledger.
 * Platform I/O is supplied by Backend; this exact coordinator is used by MainActivity and JVM tests.
 */
object PatchReceiptRecoveryCoordinator {
    data class Receipt(
        val transactionId: String,
        val changeSetId: String,
        val chatId: String,
        val snapshotId: String,
        val beforeWorkspaceIdentity: String,
        val workspaceIdentity: String,
        val rolledBack: Boolean,
        val rollbackRequested: Boolean
    )

    data class LedgerEntry(val chatId: String, val status: String, val snapshotId: String)
    data class Ledger(val valid: Boolean, val entry: LedgerEntry?)
    data class Outcome(val receipt: Receipt, val eligibleForActivation: Boolean, val effectiveRolledBack: Boolean)

    interface Backend {
        fun recoverRequestedRollback(receipt: Receipt): Receipt
        fun verifySnapshot(snapshotId: String): Boolean
        fun snapshotIdentity(snapshotId: String): String?
        fun readLedger(changeSetId: String): Ledger
        fun currentWorkspaceIdentity(): String?
        fun recordSnapshot(changeSetId: String, snapshotId: String): Boolean
        fun transition(changeSetId: String, nextStatus: String): Boolean
        fun invalidateActiveScope(changeSetId: String): Boolean
    }

    fun reconcile(initial: Receipt, backend: Backend): Outcome {
        // Validate the durable receipt before any backend I/O. A malformed or contradictory
        // receipt must never trigger rollback, snapshot reads, or ledger mutations.
        require(initial.transactionId.isNotBlank()) { "Receipt nie zawiera transactionId." }
        require(initial.changeSetId.isNotBlank()) { "Receipt nie zawiera changeSetId." }
        require(initial.chatId.isNotBlank()) { "Receipt nie zawiera chatId." }
        require(initial.snapshotId.isNotBlank()) { "Receipt nie zawiera snapshotId." }
        require(initial.beforeWorkspaceIdentity.isNotBlank()) { "Receipt nie zawiera bazowej tożsamości workspace." }
        require(initial.workspaceIdentity.isNotBlank()) { "Receipt nie zawiera docelowej tożsamości workspace." }
        require(!(initial.rolledBack && initial.rollbackRequested)) {
            "Receipt ma sprzeczne flagi rollback: operacja jest jednocześnie zakończona i oczekująca."
        }
        // Fence the workspace before invoking rollback recovery as well as before ledger
        // access. A stale receipt must not be allowed to roll back a newer, unrelated import.
        fun requireReceiptWorkspaceScope(): String {
            val current = backend.currentWorkspaceIdentity()
                ?: error("Nie można odczytać tożsamości bieżącego workspace.")
            check(current == initial.beforeWorkspaceIdentity || current == initial.workspaceIdentity) {
                "Bieżący workspace nie odpowiada ani stanowi bazowemu, ani docelowemu z receipt; recovery zatrzymano."
            }
            return current
        }
        requireReceiptWorkspaceScope()
        var receipt = initial
        if (receipt.rollbackRequested && !receipt.rolledBack) {
            val recovered = backend.recoverRequestedRollback(receipt)
            check(
                recovered.transactionId == initial.transactionId &&
                    recovered.changeSetId == initial.changeSetId &&
                    recovered.chatId == initial.chatId &&
                    recovered.snapshotId == initial.snapshotId &&
                    recovered.beforeWorkspaceIdentity == initial.beforeWorkspaceIdentity &&
                    recovered.workspaceIdentity == initial.workspaceIdentity
            ) {
                "Odzyskiwanie rollbacku zwróciło receipt o zmienionej tożsamości transakcji."
            }
            check(recovered.rolledBack && !recovered.rollbackRequested) {
                "Backend nie potwierdził trwałego zakończenia żądanego rollbacku."
            }
            receipt = recovered
        }
        // Re-check after rollback recovery: that operation may legitimately move the
        // workspace from the receipt's target identity back to its base identity.
        val currentWorkspaceIdentity = requireReceiptWorkspaceScope()
        check(backend.verifySnapshot(receipt.snapshotId)) {
            "Snapshot transakcji patcha jest uszkodzony; potwierdzenie pozostawiono do ręcznej kontroli."
        }
        check(backend.snapshotIdentity(receipt.snapshotId) == receipt.beforeWorkspaceIdentity) {
            "Snapshot nie odpowiada bazowej tożsamości workspace zapisanej w potwierdzeniu patcha."
        }
        val ledger = backend.readLedger(receipt.changeSetId)
        check(ledger.valid) { "Rejestr ChangeSet jest uszkodzony; odzyskiwanie nie może bezpiecznie przypisać patcha." }
        val change = ledger.entry ?: error("ChangeSet z potwierdzenia nie istnieje lub nie jest jednoznaczny.")
        check(change.chatId == receipt.chatId && (change.snapshotId.isBlank() || change.snapshotId == receipt.snapshotId)) {
            "Potwierdzenie patcha nie zgadza się z czatem lub snapshotem ChangeSet."
        }
        val decision = PatchReceiptRecoveryPolicy.decide(
            PatchReceiptRecoveryPolicy.Input(
                receiptRolledBack = receipt.rolledBack,
                currentWorkspaceIdentity = currentWorkspaceIdentity,
                beforeWorkspaceIdentity = receipt.beforeWorkspaceIdentity,
                changeStatus = change.status,
                ledgerSnapshotId = change.snapshotId
            )
        )
        if (decision.attachSnapshot) check(backend.recordSnapshot(receipt.changeSetId, receipt.snapshotId)) {
            "Nie udało się odtworzyć powiązania snapshotu w rejestrze."
        }
        decision.transitionTo?.let { next ->
            check(backend.transition(receipt.changeSetId, next)) { "Nie udało się odtworzyć statusu ChangeSet: $next." }
        }
        if (decision.invalidateActiveScope) check(backend.invalidateActiveScope(receipt.changeSetId)) {
            "Nie udało się unieważnić aktywnego zakresu po odzyskanym rollbacku."
        }
        return Outcome(receipt, decision.eligibleForActivation, decision.effectiveRolledBack)
    }
}
