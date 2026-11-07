package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectWorkspaceStoreApplyTest {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { stream -> files.forEach { (path, content) ->
            stream.putNextEntry(ZipEntry(path))
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.closeEntry()
        } }
        return out.toByteArray()
    }

    private fun store(vararg files: Pair<String, String>): Pair<ProjectWorkspaceStore, File> {
        val root = Files.createTempDirectory("aimaster-workspace-test").toFile()
        val store = ProjectWorkspaceStore(root)
        store.importZip(ByteArrayInputStream(zip(*files)), "fixture.zip")
        return store to root
    }

    private fun preview(store: ProjectWorkspaceStore, path: String, base: String, diff: String): ProjectPatchEngine.Preview =
        ProjectPatchEngine.preview(
            listOf(ProjectPatchEngine.Proposal(path, if (base.isEmpty()) "" else hash(base), diff)),
            store.listFiles()
        ) { filePath, limit -> store.readTextFile(filePath, limit) }

    @Test fun appliesExactModificationAndCreatesImmutableSnapshot() {
        val (store, root) = store("src/Foo.kt" to "old\n", "README.md" to "readme\n")
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -1 +1 @@\n-old\n+new\n"
        val patch = preview(store, "src/Foo.kt", "old\n", diff)
        assertTrue(patch.accepted)
        val applied = store.applyPreview(patch)
        assertEquals("new\n", store.readTextFile("src/Foo.kt").content)
        assertTrue(store.verifyCurrentWorkspace().valid)
        assertEquals(1, store.snapshots().size)
        assertEquals("old\n", File(root, "snapshots/${applied.snapshot.id}/project/src/Foo.kt").readText())
    }

    @Test fun addsNewFileAndUpdatesManifest() {
        val (store, _) = store("README.md" to "readme\n")
        val diff = "--- /dev/null\n+++ b/docs/NEW.md\n@@ -0,0 +1,1 @@\n+hello\n"
        val patch = preview(store, "docs/NEW.md", "", diff)
        assertTrue(patch.accepted)
        store.applyPreview(patch)
        assertEquals("hello\n", store.readTextFile("docs/NEW.md").content)
        assertTrue(store.verifyCurrentWorkspace().valid)
    }

    @Test fun deletesFileOnlyWhenWorkspaceRemainsNonEmpty() {
        val (store, _) = store("docs/OLD.md" to "remove me\n", "README.md" to "keep\n")
        val diff = "--- a/docs/OLD.md\n+++ /dev/null\n@@ -1 +0,0 @@\n-remove me\n"
        val patch = preview(store, "docs/OLD.md", "remove me\n", diff)
        assertTrue(patch.accepted)
        store.applyPreview(patch)
        assertFalse(store.listFiles().any { it.path == "docs/OLD.md" })
        assertTrue(store.verifyCurrentWorkspace().valid)
    }

    @Test fun rejectsStaleWorkspaceWithoutChangingAnyFile() {
        val (store, root) = store("src/Foo.kt" to "old\n", "README.md" to "keep\n")
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -1 +1 @@\n-old\n+new\n"
        val patch = preview(store, "src/Foo.kt", "old\n", diff)
        File(root, "current/src/Foo.kt").writeText("external edit\n")
        assertThrows(IllegalArgumentException::class.java) { store.applyPreview(patch) }
        assertEquals("external edit\n", File(root, "current/src/Foo.kt").readText())
    }

    @Test fun rejectsPatchWithIncorrectResultHash() {
        val (store, _) = store("src/Foo.kt" to "old\n", "README.md" to "keep\n")
        val diff = "--- a/src/Foo.kt\n+++ b/src/Foo.kt\n@@ -1 +1 @@\n-old\n+new\n"
        val valid = preview(store, "src/Foo.kt", "old\n", diff)
        val corrupted = valid.copy(files = valid.files.map { it.copy(resultSha256 = "0".repeat(64)) })
        assertThrows(IllegalArgumentException::class.java) { store.applyPreview(corrupted) }
        assertEquals("old\n", store.readTextFile("src/Foo.kt").content)
    }

    @Test fun refusesDeletingLastFileAndRemovesUnusedSnapshot() {
        val (store, _) = store("docs/OLD.md" to "remove me\n")
        val diff = "--- a/docs/OLD.md\n+++ /dev/null\n@@ -1 +0,0 @@\n-remove me\n"
        val patch = preview(store, "docs/OLD.md", "remove me\n", diff)
        assertTrue(patch.accepted)
        assertThrows(IllegalArgumentException::class.java) { store.applyPreview(patch) }
        assertEquals(0, store.snapshots().size)
        assertTrue(store.verifyCurrentWorkspace().valid)
    }

    @Test fun recoversPreviousWorkspaceAfterInterruptedDirectorySwap() {
        val (store, root) = store("README.md" to "stable\n")
        val current = File(root, "current")
        val manifest = File(root, "current.manifest")
        val previous = File(root, ".previous-crash-test")
        val previousManifest = File(root, ".previous-crash-test.manifest")
        assertTrue(current.renameTo(previous))
        assertTrue(manifest.renameTo(previousManifest))

        val restored = store.currentInfo()
        assertNotNull(restored)
        assertEquals("stable\n", store.readTextFile("README.md").content)
        assertTrue(store.verifyCurrentWorkspace().valid)
        assertFalse(previous.exists())
        assertFalse(previousManifest.exists())
    }

    @Test fun removesAbandonedPatchStagingOnlyAfterCurrentWorkspaceIsHealthy() {
        val (store, root) = store("README.md" to "stable\n")
        val abandonedTree = File(root, ".patch-staging-abandoned")
        assertTrue(abandonedTree.mkdirs())
        File(abandonedTree, "partial.tmp").writeText("partial")
        val abandonedManifest = File(root, ".patch-staging-abandoned.manifest")
        abandonedManifest.writeText("partial manifest")

        assertNotNull(store.currentInfo())
        assertTrue(store.verifyCurrentWorkspace().valid)
        assertFalse(abandonedTree.exists())
        assertFalse(abandonedManifest.exists())
    }

    @Test fun restoresVerifiedSnapshotTransactionallyAndKeepsSafetySnapshot() {
        val (store, _) = store("README.md" to "before\n", "src/A.kt" to "old\n")
        val patch = preview(store, "src/A.kt", "old\n", "--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-old\n+new\n")
        val applied = store.applyPreview(patch)
        assertEquals("new\n", store.readTextFile("src/A.kt").content)
        val restored = store.restoreSnapshot(applied.snapshot.id)
        assertEquals("old\n", store.readTextFile("src/A.kt").content)
        assertTrue(store.verifyCurrentWorkspace().valid)
        assertEquals("fixture.zip", restored.archiveName)
        assertTrue("Restore should retain the pre-restore workspace as a safety snapshot", store.snapshots().size >= 2)
    }

    @Test fun restoreSnapshotRepairsTamperedCurrentWorkspace() {
        val (store, root) = store("README.md" to "before\n", "src/A.kt" to "old\n")
        val patch = preview(store, "src/A.kt", "old\n", "--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-old\n+new\n")
        val applied = store.applyPreview(patch)
        File(root, "current/src/A.kt").writeText("tampered\n")
        assertFalse(store.verifyCurrentWorkspace().valid)
        store.restoreSnapshot(applied.snapshot.id)
        assertEquals("old\n", store.readTextFile("src/A.kt").content)
        assertTrue(store.verifyCurrentWorkspace().valid)
    }

    @Test fun refusesCorruptedSnapshotWithoutChangingCurrentWorkspace() {
        val (store, root) = store("README.md" to "before\n", "src/A.kt" to "old\n")
        val patch = preview(store, "src/A.kt", "old\n", "--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-old\n+new\n")
        val applied = store.applyPreview(patch)
        File(root, "snapshots/${applied.snapshot.id}/project/src/A.kt").writeText("tampered\n")
        assertFalse(store.verifySnapshot(applied.snapshot.id).valid)
        assertThrows(IllegalArgumentException::class.java) { store.restoreSnapshot(applied.snapshot.id) }
        assertEquals("new\n", store.readTextFile("src/A.kt").content)
    }

    @Test fun rejectsNonTextFileTargets() {
        val (store, _) = store("README.md" to "readme\n")
        val binaryLike = ProjectPatchEngine.Preview(
            true,
            listOf(ProjectPatchEngine.FilePreview("app/release.apk", "ADD", "", hash("not a real apk"), 0, 1, "not a real apk")),
            emptyList()
        )
        assertThrows(IllegalArgumentException::class.java) { store.applyPreview(binaryLike) }
        assertTrue(store.verifyCurrentWorkspace().valid)
        assertEquals(0, store.snapshots().size)
    }

    @Test fun currentIdentityChangesWhenWorkspaceManifestChanges() {
        val (store, _) = store("src/A.kt" to "old\n", "README.md" to "keep\n")
        val before = store.currentIdentity()
        assertNotNull(before)
        assertEquals(before, store.currentIdentity())
        val patch = preview(store, "src/A.kt", "old\n", "--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-old\n+new\n")
        assertTrue(patch.accepted)
        val applied = store.applyPreview(patch)
        val after = store.currentIdentity()
        assertNotNull(after)
        assertNotEquals(before, after)
        assertEquals(after, applied.workspaceIdentity)
        assertEquals(after, store.currentIdentity())
    }


    @Test fun refusesImportWhilePatchReceiptIsAwaitingChangeSetReconciliation() {
        val (store, root) = store("README.md" to "before\n", "src/A.kt" to "old\n")
        val diff = "--- a/src/A.kt\n+++ b/src/A.kt\n@@ -1 +1 @@\n-old\n+new\n"
        val patch = preview(store, "src/A.kt", "old\n", diff)
        assertTrue(patch.accepted)
        val applied = store.applyPreview(patch, changeSetId = "changeset-pending", chatId = "chat-pending")

        val nextArchive = ByteArrayInputStream(zip("README.md" to "replacement\n"))
        val importResult = runCatching { store.importZip(nextArchive, "replacement.zip") }

        assertTrue("import must not replace a workspace with an unacknowledged patch receipt", importResult.isFailure)
        assertEquals("new\n", store.readTextFile("src/A.kt").content)
        assertEquals(applied.workspaceIdentity, store.currentIdentity())
        assertTrue("pending receipt must remain available for host reconciliation", store.hasPendingWorkspaceTransaction())
        assertTrue(store.verifyCurrentWorkspace().valid)
        assertTrue(root.isDirectory)
    }


    @Test fun manualRestoreCannotBypassPendingPatchReceiptButExplicitRollbackCan() {
        val (store, _) = store("README.md" to "before\\n", "src/A.kt" to "old\\n")
        val patch = preview(store, "src/A.kt", "old\\n", "--- a/src/A.kt\\n+++ b/src/A.kt\\n@@ -1 +1 @@\\n-old\\n+new\\n")
        val applied = store.applyPreview(patch, changeSetId = "changeset-rollback", chatId = "chat-rollback")

        assertTrue(runCatching { store.restoreSnapshot(applied.snapshot.id) }.isFailure)
        assertEquals("new\\n", store.readTextFile("src/A.kt").content)

        assertTrue(store.markPatchReceiptRollbackRequested("changeset-rollback", applied.transactionId))
        store.restoreSnapshotForPatchRollback(applied.snapshot.id, "changeset-rollback", applied.transactionId)
        assertTrue(store.markPatchReceiptRolledBack("changeset-rollback", applied.transactionId))
        assertEquals("old\\n", store.readTextFile("src/A.kt").content)
        assertTrue(store.verifyCurrentWorkspace().valid)
        assertTrue(store.acknowledgePatchReceipt("changeset-rollback", applied.transactionId))
        assertFalse(store.hasPendingWorkspaceTransaction())
    }

}
