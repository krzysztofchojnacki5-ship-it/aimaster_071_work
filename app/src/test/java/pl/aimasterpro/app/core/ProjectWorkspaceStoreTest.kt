package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectWorkspaceStoreTest {
    private fun tempDir(prefix: String): File = java.nio.file.Files.createTempDirectory(prefix).toFile()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun importsFilesAndCreatesManifestWithHashes() {
        val root = tempDir("workspace-import-")
        try {
            val store = ProjectWorkspaceStore(root)
            val info = store.importZip(ByteArrayInputStream(zip("app/src/Main.kt" to "fun main() {}", "README.md" to "hello")), "project.zip", 100L)
            assertEquals(2, info.fileCount)
            assertEquals(100L, store.currentInfo()?.importedAt)
            assertEquals(2, store.listFiles().size)
            assertEquals("fun main() {}", File(root, "current/app/src/Main.kt").readText())
            assertTrue(info.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
            assertTrue(store.verifyCurrentWorkspace().valid)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsZipSlipAndLeavesExistingWorkspaceUntouched() {
        val root = tempDir("workspace-slip-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("README.md" to "safe")), "safe.zip", 1L)
            val failure = runCatching { store.importZip(ByteArrayInputStream(zip("../escape.txt" to "bad")), "bad.zip", 2L) }
            assertTrue(failure.isFailure)
            assertEquals("safe", File(root, "current/README.md").readText())
            assertFalse(File(root.parentFile, "escape.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun excludesSecretsAndBuildArtifacts() {
        val root = tempDir("workspace-filter-")
        try {
            val store = ProjectWorkspaceStore(root)
            val info = store.importZip(ByteArrayInputStream(zip(
                "app/src/Main.kt" to "source",
                "local.properties" to "sdk.dir=/secret",
                ".env.production" to "API_KEY=secret",
                ".env/private.txt" to "API_KEY=nested-secret",
                "build/output.apk" to "binary",
                ".git/config" to "git metadata"
            )), "project.zip")
            assertEquals(listOf("app/src/Main.kt"), info.files.map { it.path })
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsDuplicatePathsCaseInsensitively() {
        val root = tempDir("workspace-duplicate-")
        try {
            val bytes = zip("src/Main.kt" to "one", "SRC/main.kt" to "two")
            assertTrue(runCatching { ProjectWorkspaceStore(root).importZip(ByteArrayInputStream(bytes), "dup.zip") }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun secondImportSnapshotsPreviousWorkspace() {
        val root = tempDir("workspace-snapshot-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("README.md" to "version one")), "one.zip", 10L)
            store.importZip(ByteArrayInputStream(zip("README.md" to "version two")), "two.zip", 20L)
            assertEquals("version two", File(root, "current/README.md").readText())
            val snapshots = store.snapshots()
            assertEquals(1, snapshots.size)
            assertEquals("version one", File(root, "snapshots/${snapshots.first().id}/project/README.md").readText())
            assertEquals(20L, snapshots.first().createdAt)
        } finally { root.deleteRecursively() }
    }


    @Test fun integrityCheckDetectsModifiedAndUnexpectedFiles() {
        val root = tempDir("workspace-integrity-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("src/Main.kt" to "original")), "project.zip")
            File(root, "current/src/Main.kt").writeText("tampered")
            File(root, "current/extra.txt").writeText("unexpected")
            val report = store.verifyCurrentWorkspace()
            assertFalse(report.valid)
            assertEquals(listOf("src/Main.kt"), report.modified)
            assertEquals(listOf("extra.txt"), report.unexpected)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsEmptyArchiveWithoutReplacingWorkspace() {
        val root = tempDir("workspace-empty-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("README.md" to "keep")), "one.zip")
            val empty = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
            assertTrue(runCatching { store.importZip(ByteArrayInputStream(empty), "empty.zip") }.isFailure)
            assertEquals("keep", File(root, "current/README.md").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun readsOnlyManifestOwnedUtf8TextAfterHashVerification() {
        val root = tempDir("workspace-read-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("app/src/Main.kt" to "fun main() = Unit", "image.png" to "not-text")), "project.zip")
            val read = store.readTextFile("app/src/Main.kt")
            assertEquals("fun main() = Unit", read.content)
            assertEquals(64, read.sha256.length)
            assertTrue(runCatching { store.readTextFile("../outside.kt") }.isFailure)
            assertTrue(runCatching { store.readTextFile("image.png") }.isFailure)
            assertTrue(runCatching { store.readTextFile("missing.kt") }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun refusesToReadTamperedSourceAndDoesNotSnapshotTamperedWorkspace() {
        val root = tempDir("workspace-tamper-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("src/Main.kt" to "original")), "one.zip", 10L)
            File(root, "current/src/Main.kt").writeText("tampered")
            assertTrue(runCatching { store.readTextFile("src/Main.kt") }.isFailure)
            val next = runCatching { store.importZip(ByteArrayInputStream(zip("README.md" to "next")), "two.zip", 20L) }
            assertTrue(next.isFailure)
            assertEquals("tampered", File(root, "current/src/Main.kt").readText())
            assertTrue(store.snapshots().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun blocksSecretLikeTextFromPreview() {
        val root = tempDir("workspace-secret-preview-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("gradle.properties" to "apiKey=abcdefghijklmnopqrstuvwxyz012345")), "project.zip")
            assertTrue(runCatching { store.readTextFile("gradle.properties") }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun blocksApiTokenAndPrivateKeyPatternsFromPreview() {
        val root = tempDir("workspace-secret-variants-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip(
                "config/one.properties" to "api_token=abcdefghijklmno12345",
                "config/two.properties" to "private_key=abcdefghijklmno12345"
            )), "secrets.zip")
            assertTrue(runCatching { store.readTextFile("config/one.properties") }.isFailure)
            assertTrue(runCatching { store.readTextFile("config/two.properties") }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsOversizedSwapJournalBeforeParsing() {
        val root = tempDir("workspace-journal-oversized-")
        try {
            val journal = File(root, "workspace.swap.journal")
            journal.writeBytes(ByteArray(16 * 1024 + 1))
            assertTrue(runCatching { ProjectWorkspaceStore(root).currentInfo() }.isFailure)
            assertTrue("corrupt journal must remain for diagnosis", journal.isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsSwapJournalWithTrailingBytes() {
        val root = tempDir("workspace-journal-trailing-")
        try {
            val journal = File(root, "workspace.swap.journal")
            java.io.DataOutputStream(journal.outputStream()).use { out ->
                out.writeInt(0x41495357) // AISW
                out.writeInt(2)
                out.writeUTF("123e4567-e89b-12d3-a456-426614174000")
                out.writeBoolean(false)
                out.writeUTF("0".repeat(64))
                out.writeBoolean(false)
                out.writeByte(0x7f)
            }
            assertTrue(runCatching { ProjectWorkspaceStore(root).currentInfo() }.isFailure)
        } finally { root.deleteRecursively() }
    }

    @Test fun recoversLegacyVersionOneSwapJournalWithoutPatchMetadata() {
        val root = tempDir("workspace-journal-v1-")
        try {
            val journal = File(root, "workspace.swap.journal")
            java.io.DataOutputStream(journal.outputStream()).use { out ->
                out.writeInt(0x41495357) // AISW
                out.writeInt(1)
                out.writeUTF("123e4567-e89b-12d3-a456-426614174000")
                out.writeBoolean(false)
                out.writeUTF("0".repeat(64))
            }
            assertNull(ProjectWorkspaceStore(root).currentInfo())
            assertFalse("successfully recovered legacy journal should be cleared", journal.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun committedSwapRecoveryPrunesOrphanedPreviousArtifactsOnlyAfterCurrentVerification() {
        val root = tempDir("workspace-journal-orphan-previous-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("README.md" to "committed")), "committed.zip", 10L)

            val orphan = File(root, ".previous-orphaned-transaction")
            assertTrue(orphan.mkdirs())
            File(orphan, "old.txt").writeText("stale backup")
            val orphanManifest = File(root, ".previous-orphaned-transaction.manifest")
            orphanManifest.writeText("stale manifest")

            val manifest = File(root, "current.manifest")
            val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(manifest.readBytes()).joinToString("") { "%02x".format(it) }
            java.io.DataOutputStream(File(root, "workspace.swap.journal").outputStream()).use { out ->
                out.writeInt(0x41495357) // AISW
                out.writeInt(2)
                out.writeUTF("123e4567-e89b-12d3-a456-426614174000")
                out.writeBoolean(true)
                out.writeUTF(expectedHash)
                out.writeBoolean(false)
            }

            assertEquals("committed", File(root, "current/README.md").readText())
            assertEquals("committed", store.currentInfo()?.let { File(root, "current/README.md").readText() })
            assertFalse("orphaned previous directory should be pruned after verified commit", orphan.exists())
            assertFalse("orphaned previous manifest should be pruned after verified commit", orphanManifest.exists())
            assertFalse(File(root, "workspace.swap.journal").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedTempEvidenceIsPreservedWhenCurrentWorkspaceIsUnverified() {
        val root = tempDir("workspace-temp-evidence-preserve-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("README.md" to "committed")), "committed.zip", 10L)
            val orphanTemp = File(root, ".workspace.swap.orphan.tmp").apply { writeText("partial journal evidence") }
            File(root, "current.manifest").writeBytes(byteArrayOf(1, 2, 3))

            runCatching { ProjectWorkspaceStore(root).currentInfo() }

            assertTrue("unverified current must not authorize deletion of transaction evidence", orphanTemp.isFile)
            assertEquals("partial journal evidence", orphanTemp.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun integrityCheckRejectsSymbolicLinks() {
        val root = tempDir("workspace-symlink-")
        val outside = File(root.parentFile, "workspace-outside-${System.nanoTime()}.txt")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("src/Main.kt" to "safe")), "project.zip")
            outside.writeText("outside")
            val target = File(root, "current/src/Main.kt")
            target.delete()
            try {
                java.nio.file.Files.createSymbolicLink(target.toPath(), outside.toPath())
            } catch (e: java.nio.file.FileSystemException) {
                org.junit.Assume.assumeNoException("Symbolic links require administrative privileges or developer mode on Windows", e)
                return
            }
            val report = store.verifyCurrentWorkspace()
            assertFalse(report.valid)
            assertTrue(report.missing.contains("src/Main.kt"))
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test fun recoversInterruptedJournaledSwapAfterStoreRecreation() {
        val root = tempDir("workspace-journal-restart-")
        try {
            val originalStore = ProjectWorkspaceStore(root)
            originalStore.importZip(ByteArrayInputStream(zip("README.md" to "stable after restart")), "stable.zip", 41L)

            val current = File(root, "current")
            val manifest = File(root, "current.manifest")
            val previous = File(root, ".previous-123e4567-e89b-12d3-a456-426614174000")
            val previousManifest = File(root, ".previous-123e4567-e89b-12d3-a456-426614174000.manifest")
            assertTrue(current.renameTo(previous))
            assertTrue(manifest.renameTo(previousManifest))

            File(root, "workspace.swap.journal").outputStream().use { stream ->
                DataOutputStream(stream).use { output ->
                    output.writeInt(0x41495357) // AISW
                    output.writeInt(2)
                    output.writeUTF("123e4567-e89b-12d3-a456-426614174000")
                    output.writeBoolean(true)
                    output.writeUTF("0".repeat(64))
                    output.writeBoolean(false)
                }
            }

            // A fresh store instance models a process restart: only durable files remain.
            val restartedStore = ProjectWorkspaceStore(root)
            val recovered = restartedStore.currentInfo()
            assertNotNull(recovered)
            assertEquals("stable after restart", restartedStore.readTextFile("README.md").content)
            assertTrue(restartedStore.verifyCurrentWorkspace().valid)
            assertFalse(File(root, "workspace.swap.journal").exists())
            assertFalse(previous.exists())
            assertFalse(previousManifest.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun temporaryWriterCleanupPreservesPathNotCreatedByWriter() {
        val root = tempDir("workspace-temp-not-owned-")
        try {
            val collision = File(root, ".workspace.swap.collision.tmp").apply { writeText("foreign evidence") }
            cleanupOwnedTemporaryFile(collision, createdByThisWriter = false)
            assertTrue("pre-existing collision must remain untouched", collision.isFile)
            assertEquals("foreign evidence", collision.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun committedWorkspaceIsNotCleanedWhenHostReceiptConflictsWithJournal() {
        val root = tempDir("workspace-receipt-conflict-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("README.md" to "previous workspace")), "previous.zip", 10L)
            val previousIdentity = store.currentIdentity()!!
            val previousManifestBytes = File(root, "current.manifest").readBytes()

            store.importZip(ByteArrayInputStream(zip("README.md" to "committed workspace")), "committed.zip", 20L)
            val currentManifest = File(root, "current.manifest")
            val currentManifestHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(currentManifest.readBytes()).joinToString("") { "%02x".format(it) }
            val transactionId = "123e4567-e89b-12d3-a456-426614174000"
            val conflictingReceiptId = "223e4567-e89b-12d3-a456-426614174000"
            val previous = File(root, ".previous-$transactionId")
            assertTrue(previous.mkdirs())
            File(previous, "README.md").writeText("previous workspace")
            File(root, ".previous-$transactionId.manifest").writeBytes(previousManifestBytes)

            java.io.DataOutputStream(File(root, "workspace.swap.journal").outputStream()).use { out ->
                out.writeInt(0x41495357) // AISW
                out.writeInt(2)
                out.writeUTF(transactionId)
                out.writeBoolean(true)
                out.writeUTF(currentManifestHash)
                out.writeBoolean(true)
                out.writeUTF("change-set-1")
                out.writeUTF("chat-1")
                out.writeUTF("snapshot-1")
                out.writeUTF(previousIdentity)
            }
            java.io.DataOutputStream(File(root, "workspace.patch.receipt").outputStream()).use { out ->
                out.writeInt(0x41495052) // AIPR
                out.writeInt(3)
                out.writeUTF(conflictingReceiptId)
                out.writeUTF("change-set-1")
                out.writeUTF("chat-1")
                out.writeUTF("snapshot-1")
                out.writeUTF(previousIdentity)
                out.writeUTF(currentManifestHash)
                out.writeBoolean(false)
                out.writeBoolean(false)
            }

            assertTrue("receipt mismatch must block startup recovery",
                runCatching { ProjectWorkspaceStore(root).currentInfo() }.isFailure)
            assertTrue("journal is retained for diagnosis/retry", File(root, "workspace.swap.journal").isFile)
            assertTrue("previous workspace is retained until receipt reconciliation", previous.isDirectory)
            assertTrue(File(root, ".previous-$transactionId.manifest").isFile)
            assertTrue(File(root, "current").isDirectory)
            assertTrue(currentManifest.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun durablePublisherFailureBeforeAtomicMovePreservesTargetAndCleansOwnedTemp() {
        val root = tempDir("workspace-publisher-move-failure-")
        try {
            val temp = File(root, "writer.tmp")
            val target = File(root, "target.bin").apply { writeText("old") }
            val result = runCatching {
                publishWorkspaceDurableFile(
                    temp, target, true,
                    writePayload = { it.write("new".toByteArray()) },
                    moveAtomically = { _, _, _ -> throw java.io.IOException("injected move failure") }
                )
            }
            assertTrue(result.isFailure)
            assertEquals("old", target.readText())
            assertFalse(temp.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun durablePublisherTreatsDirectorySyncAsBestEffortAfterAtomicPublish() {
        val root = tempDir("workspace-publisher-directory-sync-")
        try {
            val temp = File(root, "writer.tmp")
            val target = File(root, "target.bin")
            publishWorkspaceDurableFile(
                temp, target, false,
                writePayload = { it.write("complete".toByteArray()) },
                syncDirectory = { throw java.io.IOException("injected directory fsync failure") }
            )
            assertEquals("complete", target.readText())
            assertFalse(temp.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun temporaryWriterCleanupUnlinksOwnedPathWithoutFollowingSymlink() {
        val root = tempDir("workspace-temp-owned-link-")
        val outside = File(root.parentFile, "workspace-temp-outside-${System.nanoTime()}.txt")
        try {
            outside.writeText("outside sentinel")
            val link = File(root, ".workspace.swap.owned.tmp")
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            } catch (e: java.nio.file.FileSystemException) {
                org.junit.Assume.assumeNoException("Symbolic links require administrative privileges or developer mode on Windows", e)
                return
            }
            cleanupOwnedTemporaryFile(link, createdByThisWriter = true)
            assertFalse("owned temporary directory entry should be unlinked", java.nio.file.Files.exists(link.toPath()))
            assertEquals("outside sentinel", outside.readText())
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }


    @Test fun manifestPublicationProducesVerifiedAtomicTargetWithoutTemporaryArtifacts() {
        val root = tempDir("workspace-manifest-atomic-")
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(zip("src/Main.kt" to "durable manifest")), "atomic.zip", 42L)
            val manifest = File(root, "current.manifest")
            assertTrue(manifest.isFile)
            assertTrue(store.verifyCurrentWorkspace().valid)
            assertEquals("durable manifest", store.readTextFile("src/Main.kt").content)
            assertTrue("manifest publication must leave no root-level temporary files", root.listFiles().orEmpty().none {
                it.isFile && it.name.endsWith(".tmp")
            })
        } finally { root.deleteRecursively() }
    }
}
