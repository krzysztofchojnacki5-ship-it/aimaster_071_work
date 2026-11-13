package pl.aimasterpro.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun archive(content: String): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry("README.md")); zip.write(content.toByteArray()); zip.closeEntry()
    }
}.toByteArray()

private data class LedgerRow(var chat: String, var status: String = "APPROVED", var snapshot: String = "")

private class FilesystemBackend(private val store: ProjectWorkspaceStore, private val row: LedgerRow) : PatchReceiptRecoveryCoordinator.Backend {
    var failTransitionOnce = false
    var invalidations = 0
    override fun recoverRequestedRollback(receipt: PatchReceiptRecoveryCoordinator.Receipt): PatchReceiptRecoveryCoordinator.Receipt {
        val result = store.recoverRequestedPatchRollback(receipt.changeSetId, receipt.transactionId)
        return receipt.copy(rolledBack = result.rolledBack, rollbackRequested = result.rollbackRequested)
    }
    override fun verifySnapshot(snapshotId: String) = store.verifySnapshot(snapshotId).valid
    override fun snapshotIdentity(snapshotId: String) = store.snapshotIdentity(snapshotId)
    override fun readLedger(changeSetId: String) = PatchReceiptRecoveryCoordinator.Ledger(true,
        PatchReceiptRecoveryCoordinator.LedgerEntry(row.chat, row.status, row.snapshot))
    override fun currentWorkspaceIdentity() = store.currentIdentity()
    override fun recordSnapshot(changeSetId: String, snapshotId: String): Boolean { row.snapshot = snapshotId; return true }
    override fun transition(changeSetId: String, nextStatus: String): Boolean {
        if (failTransitionOnce) { failTransitionOnce = false; return false }
        row.status = nextStatus; return true
    }
    override fun invalidateActiveScope(changeSetId: String): Boolean { invalidations++; return true }
}

private fun patch(root: File): Pair<ProjectWorkspaceStore, ProjectWorkspaceStore.PendingPatchReceipt> {
    val store = ProjectWorkspaceStore(root)
    val imported = store.importZip(ByteArrayInputStream(archive("before")), "source.zip", 100L)
    val file = imported.files.single()
    val preview = ProjectPatchEngine.preview(listOf(ProjectPatchEngine.Proposal("README.md", file.sha256,
        "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-before\n+after")), imported.files) { path, max -> store.readTextFile(path, max) }
    check(preview.accepted)
    store.applyPreview(preview, changeSetId = "cs-fs", chatId = "chat-fs")
    return store to checkNotNull(store.readPendingPatchReceipt())
}

fun main() {
    val roots = mutableListOf<File>()
    fun root() = Files.createTempDirectory("receipt-fs-").toFile().also(roots::add)
    try {
        run {
            val (store, pending) = patch(root()); val row = LedgerRow(pending.chatId); val backend = FilesystemBackend(store, row)
            val receipt = PatchReceiptRecoveryCoordinator.Receipt(pending.transactionId, pending.changeSetId, pending.chatId,
                pending.snapshotId, pending.beforeWorkspaceIdentity, pending.workspaceIdentity, pending.rolledBack, pending.rollbackRequested)
            val first = PatchReceiptRecoveryCoordinator.reconcile(receipt, backend)
            check(first.eligibleForActivation && row.status == "APPLIED_UNVERIFIED" && row.snapshot == pending.snapshotId)
            val second = PatchReceiptRecoveryCoordinator.reconcile(receipt, backend)
            check(second.eligibleForActivation && row.status == "APPLIED_UNVERIFIED")
            check(store.readPendingPatchReceipt() != null)
            check(store.acknowledgePatchReceipt(pending.changeSetId, pending.transactionId))
            check(store.readPendingPatchReceipt() == null)
            println("PASS real workspace patch reconciles idempotently and acknowledges exact receipt")
        }
        run {
            val (store, pending) = patch(root()); val row = LedgerRow(pending.chatId); val backend = FilesystemBackend(store, row)
            backend.failTransitionOnce = true
            val receipt = PatchReceiptRecoveryCoordinator.Receipt(pending.transactionId, pending.changeSetId, pending.chatId,
                pending.snapshotId, pending.beforeWorkspaceIdentity, pending.workspaceIdentity, false, false)
            check(runCatching { PatchReceiptRecoveryCoordinator.reconcile(receipt, backend) }.isFailure)
            check(row.snapshot == pending.snapshotId && row.status == "APPROVED")
            val retry = PatchReceiptRecoveryCoordinator.reconcile(receipt, backend)
            check(retry.eligibleForActivation && row.status == "APPLIED_UNVERIFIED")
            println("PASS real filesystem snapshot survives ledger transition failure and retry")
        }
        run {
            val (store, pending) = patch(root()); val row = LedgerRow("foreign-chat"); val backend = FilesystemBackend(store, row)
            val receipt = PatchReceiptRecoveryCoordinator.Receipt(pending.transactionId, pending.changeSetId, pending.chatId,
                pending.snapshotId, pending.beforeWorkspaceIdentity, pending.workspaceIdentity, false, false)
            check(runCatching { PatchReceiptRecoveryCoordinator.reconcile(receipt, backend) }.isFailure)
            check(row.status == "APPROVED" && row.snapshot.isBlank())
            println("PASS real filesystem receipt cannot mutate a ledger row owned by another chat")
        }
        run {
            val (store, pending) = patch(root()); val row = LedgerRow(pending.chatId); val backend = FilesystemBackend(store, row)
            check(store.markPatchReceiptRollbackRequested(pending.changeSetId, pending.transactionId))
            val requested = checkNotNull(store.readPendingPatchReceipt())
            val receipt = PatchReceiptRecoveryCoordinator.Receipt(requested.transactionId, requested.changeSetId, requested.chatId,
                requested.snapshotId, requested.beforeWorkspaceIdentity, requested.workspaceIdentity, requested.rolledBack, requested.rollbackRequested)
            val outcome = PatchReceiptRecoveryCoordinator.reconcile(receipt, backend)
            check(outcome.effectiveRolledBack && !outcome.eligibleForActivation)
            check(row.status == "REJECTED" && backend.invalidations == 1)
            check(store.currentIdentity() == pending.beforeWorkspaceIdentity)
            println("PASS real filesystem rollback restores exact base and invalidates active scope")
        }
        run {
            val (store, pending) = patch(root()); val row = LedgerRow(pending.chatId); val backend = FilesystemBackend(store, row)
            val receipt = PatchReceiptRecoveryCoordinator.Receipt(pending.transactionId, pending.changeSetId, pending.chatId,
                pending.snapshotId, pending.beforeWorkspaceIdentity, pending.workspaceIdentity, false, false)
            val recoveryEntered = CountDownLatch(1)
            val releaseRecovery = CountDownLatch(1)
            val importFinished = AtomicBoolean(false)
            val recoveryThread = Thread {
                ProjectWorkspaceOperationLock.withLock {
                    recoveryEntered.countDown()
                    check(releaseRecovery.await(5, TimeUnit.SECONDS))
                    PatchReceiptRecoveryCoordinator.reconcile(receipt, backend)
                }
            }
            val importThread = Thread {
                ProjectWorkspaceOperationLock.withLock {
                    store.importZip(ByteArrayInputStream(archive("imported-after-recovery")), "later.zip", 200L)
                    importFinished.set(true)
                }
            }
            recoveryThread.start()
            check(recoveryEntered.await(5, TimeUnit.SECONDS))
            importThread.start()
            Thread.sleep(80)
            check(!importFinished.get()) { "import entered while recovery owned the process-wide workspace lock" }
            releaseRecovery.countDown()
            recoveryThread.join(5000); importThread.join(5000)
            check(!recoveryThread.isAlive && !importThread.isAlive && importFinished.get())
            check(store.currentInfo()?.files?.single()?.let { store.readTextFile(it.path, 1024).content } == "imported-after-recovery")
            println("PASS real filesystem import waits for recovery under the shared workspace lock")
        }
        run {
            val (store, _) = patch(root()); val row = LedgerRow("chat-fs"); val backend = FilesystemBackend(store, row)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val reconcileCount = java.util.concurrent.atomic.AtomicInteger(0)
            val ackCount = java.util.concurrent.atomic.AtomicInteger(0)
            val receiptWorkers = (1..2).map {
                Thread {
                    ProjectWorkspaceOperationLock.withLock {
                        entered.countDown()
                        if (it == 1) check(release.await(5, TimeUnit.SECONDS))
                        val pending = store.readPendingPatchReceipt() ?: return@withLock
                        val receipt = PatchReceiptRecoveryCoordinator.Receipt(pending.transactionId, pending.changeSetId,
                            pending.chatId, pending.snapshotId, pending.beforeWorkspaceIdentity, pending.workspaceIdentity,
                            pending.rolledBack, pending.rollbackRequested)
                        PatchReceiptRecoveryCoordinator.reconcile(receipt, backend)
                        reconcileCount.incrementAndGet()
                        if (store.acknowledgePatchReceipt(pending.changeSetId, pending.transactionId)) ackCount.incrementAndGet()
                    }
                }
            }
            receiptWorkers[0].start()
            check(entered.await(5, TimeUnit.SECONDS))
            receiptWorkers[1].start()
            Thread.sleep(80)
            release.countDown()
            receiptWorkers.forEach { it.join(5000); check(!it.isAlive) }
            check(reconcileCount.get() == 1 && ackCount.get() == 1 && store.readPendingPatchReceipt() == null)
            println("PASS concurrent real-filesystem recovery callers reconcile and acknowledge one receipt once")
        }
        run {
            val (store, pending) = patch(root()); val row = LedgerRow(pending.chatId); val backend = FilesystemBackend(store, row)
            val receipt = PatchReceiptRecoveryCoordinator.Receipt(pending.transactionId, pending.changeSetId, pending.chatId,
                pending.snapshotId, pending.beforeWorkspaceIdentity, pending.workspaceIdentity, false, false)
            val outcome = ProjectWorkspaceOperationLock.withLock { PatchReceiptRecoveryCoordinator.reconcile(receipt, backend) }
            check(outcome.eligibleForActivation)
            ProjectWorkspaceOperationLock.withLock {
                store.importZip(ByteArrayInputStream(archive("new-import")), "replacement.zip", 300L)
            }
            val activationAllowed = runCatching {
                ProjectWorkspaceIdentityGuard.requireUnchanged(receipt.workspaceIdentity, store.currentIdentity())
            }.isSuccess
            check(!activationAllowed)
            println("PASS import after receipt preparation changes workspace identity and blocks stale activation")
        }
        run {
            val workspaceRoot = root()
            val (store, pending) = patch(workspaceRoot)
            check(store.markPatchReceiptRollbackRequested(pending.changeSetId, pending.transactionId))
            // Simulate process death after the snapshot was restored but before the receipt
            // could be durably marked rolledBack=true.
            store.restoreSnapshot(pending.snapshotId, 400L)
            val restartedStore = ProjectWorkspaceStore(workspaceRoot)
            val restartedPending = checkNotNull(restartedStore.readPendingPatchReceipt())
            check(restartedPending.rollbackRequested && !restartedPending.rolledBack)
            val row = LedgerRow(restartedPending.chatId)
            val backend = FilesystemBackend(restartedStore, row)
            val receipt = PatchReceiptRecoveryCoordinator.Receipt(
                restartedPending.transactionId, restartedPending.changeSetId, restartedPending.chatId,
                restartedPending.snapshotId, restartedPending.beforeWorkspaceIdentity,
                restartedPending.workspaceIdentity, restartedPending.rolledBack, restartedPending.rollbackRequested
            )
            val outcome = PatchReceiptRecoveryCoordinator.reconcile(receipt, backend)
            check(outcome.effectiveRolledBack && row.status == "REJECTED" && backend.invalidations == 1)
            check(restartedStore.currentIdentity() == pending.beforeWorkspaceIdentity)
            check(checkNotNull(restartedStore.readPendingPatchReceipt()).rolledBack)
            check(restartedStore.acknowledgePatchReceipt(pending.changeSetId, pending.transactionId))
            println("PASS process-recreated store completes rollback after restore-before-receipt-write interruption")
        }
        run {
            val workspaceRoot = root()
            val (store, pending) = patch(workspaceRoot)
            val row = LedgerRow(pending.chatId)
            val backend = FilesystemBackend(store, row)
            val receipt = PatchReceiptRecoveryCoordinator.Receipt(
                pending.transactionId, pending.changeSetId, pending.chatId, pending.snapshotId,
                pending.beforeWorkspaceIdentity, pending.workspaceIdentity, false, false
            )
            val recoveryEntered = CountDownLatch(1)
            val releaseRecovery = CountDownLatch(1)
            val restoreFinished = AtomicBoolean(false)
            val recoveryThread = Thread {
                ProjectWorkspaceOperationLock.withLock {
                    PatchReceiptRecoveryCoordinator.reconcile(receipt, backend)
                    recoveryEntered.countDown()
                    check(releaseRecovery.await(5, TimeUnit.SECONDS))
                }
            }
            val restoreThread = Thread {
                ProjectWorkspaceOperationLock.withLock {
                    store.restoreSnapshot(pending.snapshotId, 500L)
                    restoreFinished.set(true)
                }
            }
            recoveryThread.start()
            check(recoveryEntered.await(5, TimeUnit.SECONDS))
            restoreThread.start()
            Thread.sleep(80)
            check(!restoreFinished.get()) { "restore interleaved while recovery owned the workspace lock" }
            releaseRecovery.countDown()
            recoveryThread.join(5000); restoreThread.join(5000)
            check(!recoveryThread.isAlive && !restoreThread.isAlive && restoreFinished.get())
            var activated = false
            var invalidated = false
            val commit = PatchReceiptRecoveryCommitGate.commit(
                eligibleForActivation = true,
                expectedWorkspaceIdentity = receipt.workspaceIdentity,
                chatStillMatches = { true },
                currentWorkspaceIdentity = { store.currentIdentity() ?: "" },
                activate = { activated = true; true },
                invalidateActiveScope = { invalidated = true },
                acknowledge = { store.acknowledgePatchReceipt(receipt.changeSetId, receipt.transactionId) }
            )
            check(commit == PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGED)
            check(!activated && invalidated && store.readPendingPatchReceipt() == null)
            check(store.currentIdentity() == pending.beforeWorkspaceIdentity)
            println("PASS workspace restore waits for recovery and stale active scope is invalidated before receipt ack")
        }
        println("PATCH_RECEIPT_FILESYSTEM_INTEGRATION: 9/9 PASS")
    } finally { roots.forEach { it.deleteRecursively() } }
}
