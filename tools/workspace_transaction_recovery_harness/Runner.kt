package pl.aimasterpro.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val JOURNAL_MAGIC = 0x41495357
private const val JOURNAL_VERSION = 1
private const val JOURNAL_VERSION_WITH_PATCH = 2

private fun checkState(value: Boolean, message: String) {
    if (!value) error("FAIL: $message")
}

private fun zip(name: String, content: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { out ->
        out.putNextEntry(ZipEntry(name))
        out.write(content.toByteArray(Charsets.UTF_8))
        out.closeEntry()
    }
    return bytes.toByteArray()
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun writeJournal(root: File, id: String, hadCurrent: Boolean, expectedHash: String) {
    DataOutputStream(FileOutputStream(File(root, "workspace.swap.journal"))).use { out ->
        out.writeInt(JOURNAL_MAGIC)
        out.writeInt(JOURNAL_VERSION)
        out.writeUTF(id)
        out.writeBoolean(hadCurrent)
        out.writeUTF(expectedHash)
    }
}

private fun writePatchJournal(
    root: File,
    id: String,
    expectedHash: String,
    changeSetId: String,
    chatId: String,
    snapshotId: String,
    beforeIdentity: String
) {
    DataOutputStream(FileOutputStream(File(root, "workspace.swap.journal"))).use { out ->
        out.writeInt(JOURNAL_MAGIC)
        out.writeInt(JOURNAL_VERSION_WITH_PATCH)
        out.writeUTF(id)
        out.writeBoolean(true)
        out.writeUTF(expectedHash)
        out.writeBoolean(true)
        out.writeUTF(changeSetId)
        out.writeUTF(chatId)
        out.writeUTF(snapshotId)
        out.writeUTF(beforeIdentity)
    }
}

private fun copyTree(source: File, destination: File) {
    source.walkTopDown().forEach { file ->
        val relative = source.toPath().relativize(file.toPath())
        val target = destination.toPath().resolve(relative)
        if (file.isDirectory) Files.createDirectories(target)
        else {
            Files.createDirectories(target.parent)
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun preparePair(root: File, content: String) {
    ProjectWorkspaceStore(root).importZip(ByteArrayInputStream(zip("README.md", content)), "test.zip", 10L)
}

fun main() {
    var passed = 0
    val roots = mutableListOf<File>()
    fun root(prefix: String): File = Files.createTempDirectory(prefix).toFile().also(roots::add)

    try {
        // Fault-injection coverage for the exact durable publisher used by both production writers.
        run {
            val dir = root("publisher-create-collision-")
            val temp = File(dir, "entry.tmp").apply { writeText("foreign") }
            val target = File(dir, "target")
            val result = runCatching { publishWorkspaceDurableFile(temp, target, true, { it.write("ours".toByteArray()) }) }
            checkState(result.isFailure && temp.readText() == "foreign" && !target.exists(),
                "CREATE_NEW collision must preserve a pre-existing temp entry")
            passed++
        }
        run {
            val dir = root("publisher-write-failure-")
            val temp = File(dir, "entry.tmp")
            val target = File(dir, "target")
            val result = runCatching {
                publishWorkspaceDurableFile(temp, target, true, { stream -> stream.write("partial".toByteArray()); error("injected write failure") })
            }
            checkState(result.isFailure && !temp.exists() && !target.exists(), "payload write failure must clean only the owned temp")
            passed++
        }
        run {
            val dir = root("publisher-flush-failure-")
            val temp = File(dir, "entry.tmp")
            val target = File(dir, "target")
            val result = runCatching {
                publishWorkspaceDurableFile(temp, target, true, { it.write("payload".toByteArray()) },
                    flushAndSync = { _, _ -> throw java.io.IOException("injected flush failure") })
            }
            checkState(result.isFailure && !temp.exists() && !target.exists(), "flush failure must not publish and must clean owned temp")
            passed++
        }
        run {
            val dir = root("publisher-fsync-failure-")
            val temp = File(dir, "entry.tmp")
            val target = File(dir, "target")
            val result = runCatching {
                publishWorkspaceDurableFile(temp, target, true, { it.write("payload".toByteArray()) },
                    flushAndSync = { output, _ -> output.flush(); throw java.io.IOException("injected fd.sync failure") })
            }
            checkState(result.isFailure && !temp.exists() && !target.exists(), "failure after file fsync but before move must not publish")
            passed++
        }
        run {
            val dir = root("publisher-atomic-move-failure-")
            val temp = File(dir, "entry.tmp")
            val target = File(dir, "target").apply { writeText("old") }
            val result = runCatching {
                publishWorkspaceDurableFile(temp, target, true, { it.write("new".toByteArray()) },
                    moveAtomically = { _, _, _ -> throw java.io.IOException("injected move failure") })
            }
            checkState(result.isFailure && target.readText() == "old" && !temp.exists(), "atomic move failure must preserve old target and clean temp")
            passed++
        }
        run {
            val dir = root("publisher-atomic-required-")
            val temp = File(dir, "entry.tmp")
            val target = File(dir, "target")
            var moveCalls = 0
            val result = runCatching {
                publishWorkspaceDurableFile(temp, target, true, { it.write("new".toByteArray()) },
                    moveAtomically = { _, _, _ -> moveCalls++; throw java.nio.file.AtomicMoveNotSupportedException("a", "b", "injected") })
            }
            checkState(result.isFailure && moveCalls == 1 && !target.exists() && !temp.exists(),
                "unsupported atomic move must fail closed without a non-atomic fallback")
            passed++
        }
        run {
            val dir = root("publisher-directory-sync-failure-")
            val temp = File(dir, "entry.tmp")
            val target = File(dir, "target")
            publishWorkspaceDurableFile(temp, target, false, { it.write("durable".toByteArray()) },
                syncDirectory = { throw java.io.IOException("injected directory fsync failure") })
            checkState(target.readText() == "durable" && !temp.exists(), "directory fsync failure must remain best-effort after atomic publication")
            passed++
        }
        run {
            val dir = root("publisher-replace-")
            val temp = File(dir, "entry.tmp")
            val target = File(dir, "target").apply { writeText("old") }
            publishWorkspaceDurableFile(temp, target, true, { it.write("new".toByteArray()) })
            checkState(target.readText() == "new" && !temp.exists(), "successful atomic replace must publish complete payload")
            passed++
        }
        run {
            val dir = root("publisher-sync-order-")
            val temp = File(dir, "entry.tmp")
            val target = File(dir, "target")
            var syncSawPublishedTarget = false
            publishWorkspaceDurableFile(temp, target, false, { it.write("ordered".toByteArray()) },
                syncDirectory = { syncSawPublishedTarget = target.readText() == "ordered" })
            checkState(syncSawPublishedTarget, "directory sync must occur only after successful atomic publication")
            passed++
        }
        run {
            val workspace = root("temp-ownership-preserve-")
            val collision = File(workspace, ".workspace.swap.collision.tmp").apply { writeText("pre-existing evidence") }
            cleanupOwnedTemporaryFile(collision, createdByThisWriter = false)
            checkState(collision.isFile && collision.readText() == "pre-existing evidence",
                "writer cleanup must preserve a colliding file it did not create")
            passed++
        }
        run {
            val workspace = root("temp-ownership-delete-")
            val owned = File(workspace, ".workspace.swap.owned.tmp").apply { writeText("owned partial") }
            cleanupOwnedTemporaryFile(owned, createdByThisWriter = true)
            checkState(!owned.exists(), "writer cleanup must remove its own incomplete temporary file")
            passed++
        }
        run {
            val workspace = root("temp-ownership-symlink-preserve-")
            val outside = File(root("temp-ownership-outside-preserve-"), "sentinel.txt").apply { writeText("keep") }
            val link = File(workspace, ".workspace.swap.foreign.tmp")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            cleanupOwnedTemporaryFile(link, createdByThisWriter = false)
            checkState(Files.isSymbolicLink(link.toPath()) && outside.readText() == "keep",
                "cleanup must preserve a symlink collision when the writer does not own the path")
            passed++
        }
        run {
            val workspace = root("temp-ownership-symlink-unlink-")
            val outside = File(root("temp-ownership-outside-unlink-"), "sentinel.txt").apply { writeText("keep") }
            val link = File(workspace, ".workspace.swap.owned-link.tmp")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            cleanupOwnedTemporaryFile(link, createdByThisWriter = true)
            checkState(!Files.exists(link.toPath()) && outside.readText() == "keep",
                "owned-path cleanup must unlink the directory entry without following its target")
            passed++
        }
        run {
            val workspace = root("swap-before-rename-")
            preparePair(workspace, "original")
            val id = UUID.randomUUID().toString()
            writeJournal(workspace, id, true, "0".repeat(64))
            val info = ProjectWorkspaceStore(workspace).currentInfo()
            checkState(info != null && File(workspace, "current/README.md").readText() == "original", "intent-only crash must preserve original workspace")
            checkState(!File(workspace, "workspace.swap.journal").exists(), "intent-only journal must be cleared")
            passed++
        }
        run {
            val workspace = root("swap-mid-")
            val incoming = root("swap-mid-new-")
            preparePair(workspace, "original")
            preparePair(incoming, "incoming")
            val id = UUID.randomUUID().toString()
            Files.move(File(workspace, "current").toPath(), File(workspace, ".previous-$id").toPath())
            Files.move(File(workspace, "current.manifest").toPath(), File(workspace, ".previous-$id.manifest").toPath())
            copyTree(File(incoming, "current"), File(workspace, "current"))
            Files.copy(File(incoming, "current.manifest").toPath(), File(workspace, "current.manifest").toPath())
            writeJournal(workspace, id, true, "0".repeat(64)) // force rollback: installed pair does not match intent
            val info = ProjectWorkspaceStore(workspace).currentInfo()
            checkState(info != null && File(workspace, "current/README.md").readText() == "original", "uncommitted swap must restore previous workspace")
            checkState(!File(workspace, ".previous-$id").exists(), "restored previous directory must be consumed")
            passed++
        }
        run {
            val workspace = root("swap-commit-")
            val incoming = root("swap-commit-new-")
            preparePair(workspace, "original")
            preparePair(incoming, "committed")
            val id = UUID.randomUUID().toString()
            Files.move(File(workspace, "current").toPath(), File(workspace, ".previous-$id").toPath())
            Files.move(File(workspace, "current.manifest").toPath(), File(workspace, ".previous-$id.manifest").toPath())
            copyTree(File(incoming, "current"), File(workspace, "current"))
            Files.copy(File(incoming, "current.manifest").toPath(), File(workspace, "current.manifest").toPath())
            writeJournal(workspace, id, true, sha256(File(workspace, "current.manifest")))
            val info = ProjectWorkspaceStore(workspace).currentInfo()
            checkState(info != null && File(workspace, "current/README.md").readText() == "committed", "complete verified pair must win as committed transaction")
            checkState(!File(workspace, ".previous-$id").exists(), "committed transaction must clean previous directory")
            passed++
        }
        run {
            val workspace = root("swap-commit-orphan-cleanup-")
            preparePair(workspace, "committed")
            val orphan = File(workspace, ".previous-orphaned-transaction")
            checkState(orphan.mkdirs(), "orphan fixture directory must be created")
            File(orphan, "old.txt").writeText("stale backup")
            File(workspace, ".previous-orphaned-transaction.manifest").writeText("stale manifest")
            val id = UUID.randomUUID().toString()
            writeJournal(workspace, id, true, sha256(File(workspace, "current.manifest")))
            val info = ProjectWorkspaceStore(workspace).currentInfo()
            checkState(info != null && File(workspace, "current/README.md").readText() == "committed", "verified committed workspace must remain active")
            checkState(!orphan.exists() && !File(workspace, ".previous-orphaned-transaction.manifest").exists(), "verified current pair must prune unrelated orphaned previous artifacts")
            checkState(!File(workspace, "workspace.swap.journal").exists(), "committed journal must be cleared after recovery")
            passed++
        }
        run {
            val workspace = root("swap-corrupt-")
            preparePair(workspace, "original")
            File(workspace, "workspace.swap.journal").writeBytes(byteArrayOf(1, 2, 3, 4))
            val result = runCatching { ProjectWorkspaceStore(workspace).currentInfo() }
            checkState(result.isFailure, "corrupt journal must fail closed")
            checkState(File(workspace, "current/README.md").readText() == "original", "corrupt journal must not mutate workspace")
            passed++
        }
        run {
            val workspace = root("patch-receipt-")
            val store = ProjectWorkspaceStore(workspace)
            val original = store.importZip(ByteArrayInputStream(zip("README.md", "old")), "source.zip", 100L)
            val base = original.files.single()
            val preview = ProjectPatchEngine.preview(
                listOf(ProjectPatchEngine.Proposal(
                    "README.md", base.sha256,
                    "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-old\n+new"
                )),
                original.files
            ) { path, maxBytes -> store.readTextFile(path, maxBytes) }
            checkState(preview.accepted, "patch preview must be accepted")
            val result = store.applyPreview(preview, changeSetId = "changeset-101", chatId = "chat-101")
            val receipt = ProjectWorkspaceStore(workspace).readPendingPatchReceipt()
            checkState(receipt != null, "committed patch must publish durable receipt")
            checkState(receipt!!.snapshotId == result.snapshot.id && receipt.changeSetId == "changeset-101" && receipt.chatId == "chat-101", "receipt must bind ChangeSet/chat/snapshot")
            checkState(result.transactionId == receipt.transactionId, "ApplyResult must expose exact durable transaction id")
            checkState(store.hasPendingWorkspaceTransaction(), "pending receipt must block another patch")
            checkState(receipt.beforeWorkspaceIdentity == store.snapshotIdentity(receipt.snapshotId), "receipt base identity must match verified snapshot")
            checkState(receipt.workspaceIdentity == store.currentIdentity(), "receipt output identity must match current manifest")
            checkState(!ProjectWorkspaceStore(workspace).acknowledgePatchReceipt("foreign-change", receipt.transactionId), "foreign ChangeSet must not acknowledge receipt")
            checkState(ProjectWorkspaceStore(workspace).readPendingPatchReceipt() != null, "foreign acknowledgement must preserve receipt")
            val current = ProjectWorkspaceStore(workspace)
            val currentInfo = current.currentInfo()!!
            val currentRecord = currentInfo.files.single()
            val secondPreview = ProjectPatchEngine.preview(
                listOf(ProjectPatchEngine.Proposal(
                    "README.md", currentRecord.sha256,
                    "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-new\n+again"
                )),
                currentInfo.files
            ) { path, maxBytes -> current.readTextFile(path, maxBytes) }
            checkState(runCatching { current.applyPreview(secondPreview) }.isFailure, "new patch must be blocked while previous receipt is pending")
            repeat(7) { index ->
                current.importZip(ByteArrayInputStream(zip("README.md", "later-$index")), "later-$index.zip", 200L + index)
            }
            checkState(ProjectWorkspaceStore(workspace).verifySnapshot(receipt.snapshotId).valid, "pending receipt snapshot must survive snapshot pruning and later imports")
            checkState(ProjectWorkspaceStore(workspace).acknowledgePatchReceipt(receipt.changeSetId, receipt.transactionId), "exact receipt acknowledgement must succeed")
            checkState(ProjectWorkspaceStore(workspace).readPendingPatchReceipt() == null, "acknowledged receipt must be removed")
            checkState(!store.hasPendingWorkspaceTransaction(), "acknowledged receipt must clear pending transaction state")
            passed++
        }
        run {
            val workspace = root("patch-receipt-recovery-")
            val incoming = root("patch-receipt-recovery-new-")
            val store = ProjectWorkspaceStore(workspace)
            store.importZip(ByteArrayInputStream(zip("README.md", "before")), "before.zip", 10L)
            val beforeIdentity = store.currentIdentity()!!
            store.importZip(ByteArrayInputStream(zip("README.md", "after")), "after.zip", 20L)
            val snapshot = store.snapshots().first()
            val id = UUID.randomUUID().toString()
            Files.move(File(workspace, "current").toPath(), File(workspace, ".previous-$id").toPath())
            Files.move(File(workspace, "current.manifest").toPath(), File(workspace, ".previous-$id.manifest").toPath())
            preparePair(incoming, "committed-after-patch")
            copyTree(File(incoming, "current"), File(workspace, "current"))
            Files.copy(File(incoming, "current.manifest").toPath(), File(workspace, "current.manifest").toPath())
            writePatchJournal(workspace, id, sha256(File(workspace, "current.manifest")), "changeset-recovery", "chat-recovery", snapshot.id, beforeIdentity)
            val recovered = ProjectWorkspaceStore(workspace).readPendingPatchReceipt()
            checkState(recovered != null && recovered.changeSetId == "changeset-recovery", "startup recovery must recreate missing patch receipt from committed journal")
            checkState(recovered!!.snapshotId == snapshot.id && recovered.beforeWorkspaceIdentity == beforeIdentity, "recovered receipt must retain exact snapshot lineage")
            passed++
        }
        run {
            val workspace = root("patch-receipt-corrupt-")
            val store = ProjectWorkspaceStore(workspace)
            store.importZip(ByteArrayInputStream(zip("README.md", "old")), "source.zip", 100L)
            val base = store.currentInfo()!!.files.single()
            val preview = ProjectPatchEngine.preview(
                listOf(ProjectPatchEngine.Proposal("README.md", base.sha256,
                    "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-old\n+new")),
                listOf(base)
            ) { path, maxBytes -> store.readTextFile(path, maxBytes) }
            store.applyPreview(preview, changeSetId = "changeset-corrupt", chatId = "chat-corrupt")
            File(workspace, "workspace.patch.receipt").writeBytes(byteArrayOf(9, 8, 7))
            checkState(runCatching { ProjectWorkspaceStore(workspace).readPendingPatchReceipt() }.isFailure, "corrupt patch receipt must fail closed")
            checkState(File(workspace, "current/README.md").readText() == "new", "corrupt receipt must not roll back or mutate committed workspace")
            passed++
        }
        run {
            val workspace = root("patch-rollback-receipt-")
            val incoming = root("patch-rollback-receipt-new-")
            val store = ProjectWorkspaceStore(workspace)
            store.importZip(ByteArrayInputStream(zip("README.md", "before")), "before.zip", 10L)
            store.importZip(ByteArrayInputStream(zip("README.md", "after")), "after.zip", 20L)
            store.importZip(ByteArrayInputStream(zip("README.md", "after")), "current.zip", 30L)
            val snapshot = store.snapshots().first()
            val beforePatchIdentity = store.currentIdentity()!!
            val id = UUID.randomUUID().toString()
            Files.move(File(workspace, "current").toPath(), File(workspace, ".previous-$id").toPath())
            Files.move(File(workspace, "current.manifest").toPath(), File(workspace, ".previous-$id.manifest").toPath())
            preparePair(incoming, "incomplete-new-workspace")
            copyTree(File(incoming, "current"), File(workspace, "current"))
            // Deliberately omit the new manifest: this is the crash window after directory rename.
            writePatchJournal(workspace, id, "0".repeat(64), "changeset-rollback", "chat-rollback", snapshot.id, beforePatchIdentity)
            val recovered = ProjectWorkspaceStore(workspace).readPendingPatchReceipt()
            checkState(recovered != null && recovered.rolledBack, "uncommitted patch swap must produce a durable rollback receipt")
            checkState(File(workspace, "current/README.md").readText() == "after", "rollback receipt must accompany restored previous workspace")
            checkState(recovered!!.beforeWorkspaceIdentity == ProjectWorkspaceStore(workspace).currentIdentity(), "rollback receipt must bind restored base identity")
            passed++
        }
        run {
            val workspace = root("patch-noop-")
            val store = ProjectWorkspaceStore(workspace)
            val imported = store.importZip(ByteArrayInputStream(zip("README.md", "same")), "source.zip", 100L)
            val record = imported.files.single()
            val preview = ProjectPatchEngine.preview(
                listOf(ProjectPatchEngine.Proposal("README.md", record.sha256,
                    "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-same\n+same")),
                imported.files
            ) { path, maxBytes -> store.readTextFile(path, maxBytes) }
            checkState(preview.accepted, "no-op diff may pass syntax preview but must be rejected at apply")
            checkState(runCatching { store.applyPreview(preview, changeSetId = "changeset-noop", chatId = "chat-noop") }.isFailure,
                "no-op patch must be rejected before swap/receipt")
            checkState(File(workspace, "current/README.md").readText() == "same" && !store.hasPendingWorkspaceTransaction(),
                "no-op rejection must preserve original workspace and not create receipt")
            passed++
        }
        run {
            val workspace = root("patch-requested-rollback-")
            val store = ProjectWorkspaceStore(workspace)
            val imported = store.importZip(ByteArrayInputStream(zip("README.md", "before")), "source.zip", 100L)
            val base = imported.files.single()
            val preview = ProjectPatchEngine.preview(
                listOf(ProjectPatchEngine.Proposal("README.md", base.sha256,
                    "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-before\n+after")),
                imported.files
            ) { path, maxBytes -> store.readTextFile(path, maxBytes) }
            val applied = store.applyPreview(preview, changeSetId = "changeset-rollback-request", chatId = "chat-rollback-request")
            val receipt = store.readPendingPatchReceipt()!!
            checkState(store.markPatchReceiptRollbackRequested(receipt.changeSetId, receipt.transactionId), "rollback intent must be durable before restore")
            val recovered = ProjectWorkspaceStore(workspace).recoverRequestedPatchRollback(receipt.changeSetId, receipt.transactionId)
            checkState(recovered.rolledBack && !recovered.rollbackRequested, "restart recovery must complete and seal requested rollback")
            checkState(File(workspace, "current/README.md").readText() == "before", "requested rollback must restore exact base snapshot")
            checkState(ProjectWorkspaceStore(workspace).currentIdentity() == receipt.beforeWorkspaceIdentity, "requested rollback must restore exact base identity")
            checkState(applied.snapshot.id == recovered.snapshotId, "rollback receipt must preserve the exact snapshot id")
            val repeated = ProjectWorkspaceStore(workspace).recoverRequestedPatchRollback(receipt.changeSetId, receipt.transactionId)
            checkState(repeated.rolledBack && File(workspace, "current/README.md").readText() == "before", "repeated recovery must be idempotent")
            passed++
        }
        run {
            val workspace = root("patch-requested-rollback-conflict-")
            val store = ProjectWorkspaceStore(workspace)
            val imported = store.importZip(ByteArrayInputStream(zip("README.md", "before")), "source.zip", 100L)
            val base = imported.files.single()
            val preview = ProjectPatchEngine.preview(
                listOf(ProjectPatchEngine.Proposal("README.md", base.sha256,
                    "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-before\n+after")),
                imported.files
            ) { path, maxBytes -> store.readTextFile(path, maxBytes) }
            val applied = store.applyPreview(preview, changeSetId = "changeset-rollback-conflict", chatId = "chat-rollback-conflict")
            val receipt = store.readPendingPatchReceipt()!!
            checkState(store.markPatchReceiptRollbackRequested(receipt.changeSetId, receipt.transactionId), "rollback request must be persisted")
            store.importZip(ByteArrayInputStream(zip("README.md", "new-project")), "new.zip", 200L)
            val result = runCatching { ProjectWorkspaceStore(workspace).recoverRequestedPatchRollback(receipt.changeSetId, receipt.transactionId) }
            checkState(result.isFailure, "rollback must fail closed when workspace has a third identity")
            checkState(File(workspace, "current/README.md").readText() == "new-project", "conflicting newer workspace must never be overwritten")
            checkState(ProjectWorkspaceStore(workspace).readPendingPatchReceipt()?.rollbackRequested == true, "conflicted rollback intent must remain pending")
            checkState(applied.snapshot.id == receipt.snapshotId, "conflict must retain the original rollback snapshot")
            passed++
        }
        run {
            val workspace = root("patch-rollback-between-phases-")
            val store = ProjectWorkspaceStore(workspace)
            val imported = store.importZip(ByteArrayInputStream(zip("README.md", "base")), "base.zip", 10L)
            val base = imported.files.single()
            val preview = ProjectPatchEngine.preview(
                listOf(ProjectPatchEngine.Proposal("README.md", base.sha256,
                    "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-base\n+patched")),
                imported.files
            ) { path, maxBytes -> store.readTextFile(path, maxBytes) }
            val applied = store.applyPreview(preview, changeSetId = "changeset-between-phases", chatId = "chat-between-phases")
            checkState(store.markPatchReceiptRollbackRequested("changeset-between-phases", applied.transactionId), "rollback intent must be saved")
            store.restoreSnapshot(applied.snapshot.id) // process death would occur before markPatchReceiptRolledBack
            val recovered = ProjectWorkspaceStore(workspace).recoverRequestedPatchRollback("changeset-between-phases", applied.transactionId)
            checkState(recovered.rolledBack && !recovered.rollbackRequested, "recovery must seal a rollback completed before process death")
            checkState(File(workspace, "current/README.md").readText() == "base", "already-restored workspace must remain unchanged")
            passed++
        }
        run {
            val workspace = root("swap-orphan-temp-cleanup-")
            preparePair(workspace, "verified-current")
            val swapTemp = File(workspace, ".workspace.swap.orphan.tmp")
            val receiptTemp = File(workspace, ".workspace.patch.receipt.orphan.tmp")
            checkState(swapTemp.writeText("partial journal").let { swapTemp.isFile }, "orphan swap temp must be created")
            checkState(receiptTemp.writeText("partial receipt").let { receiptTemp.isFile }, "orphan receipt temp must be created")
            val outside = File(root("swap-temp-symlink-target-"), "keep.txt").apply { writeText("outside sentinel") }
            val symlinkTemp = File(workspace, ".workspace.swap.symlink.tmp")
            Files.createSymbolicLink(symlinkTemp.toPath(), outside.toPath())
            val recovered = ProjectWorkspaceStore(workspace).currentInfo()
            checkState(recovered != null, "verified current workspace must remain available during temp cleanup")
            checkState(!swapTemp.exists() && !receiptTemp.exists() && !Files.exists(symlinkTemp.toPath()),
                "verified recovery must remove orphan transaction temp entries")
            checkState(outside.readText() == "outside sentinel", "temp symlink cleanup must never follow or delete its target")
            passed++
        }
        run {
            val workspace = root("swap-orphan-temp-preserve-invalid-current-")
            preparePair(workspace, "current-before-corruption")
            val swapTemp = File(workspace, ".workspace.swap.must-preserve.tmp").apply { writeText("partial journal") }
            File(workspace, "current.manifest").writeBytes(byteArrayOf(1, 2, 3))
            runCatching { ProjectWorkspaceStore(workspace).currentInfo() }
            checkState(swapTemp.isFile && swapTemp.readText() == "partial journal",
                "orphan temp cleanup must not run when current workspace cannot be verified")
            passed++
        }
        println("WORKSPACE_TRANSACTION_RECOVERY: $passed/28 PASS")
    } finally {
        roots.forEach { it.deleteRecursively() }
    }
}
