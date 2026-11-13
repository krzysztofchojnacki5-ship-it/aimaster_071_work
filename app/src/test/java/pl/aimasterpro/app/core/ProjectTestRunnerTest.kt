package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectTestRunnerTest {
    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> files.forEach { (path, content) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
        } }
        return out.toByteArray()
    }

    @Test fun safeRunnerReportsNotRunEvenWhenIntegrityChecksPass() {
        val root = Files.createTempDirectory("aimaster-runner").toFile()
        val store = ProjectWorkspaceStore(root)
        store.importZip(ByteArrayInputStream(zip("README.md" to "before\n")), "fixture.zip")
        val patch = ProjectPatchEngine.preview(
            listOf(ProjectPatchEngine.Proposal("README.md", store.readTextFile("README.md").sha256,
                "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-before\n+after\n")),
            store.listFiles()
        ) { path, limit -> store.readTextFile(path, limit) }
        assertTrue(patch.accepted)
        val applied = store.applyPreview(patch)
        val result = ProjectTestRunner(store).verifyAppliedChange(applied.snapshot.id, applied.changedPaths, "change-1", "chat-1")
        assertEquals(ProjectTestRunner.Status.NOT_RUN, result.status)
        assertFalse(result.projectCodeExecuted)
        assertTrue(result.checks.filter { it.name != "PROJECT_TESTS" }.all { it.status == ProjectTestRunner.Status.PASS })
        assertEquals(ProjectTestRunner.Status.NOT_RUN, result.checks.single { it.name == "PROJECT_TESTS" }.status)
        assertEquals(4, result.evidence.size)
        assertTrue(result.evidence.all { it.taskId == "change-1" && it.chatId == "chat-1" && it.snapshotId == applied.snapshot.id })
        assertEquals(VerificationAssessmentStatus.INCOMPLETE, result.evidenceAssessment.status)
        assertTrue(result.evidence.any { it.kind == VerificationEvidenceKind.PROJECT_TESTS && it.status == VerificationEvidenceStatus.NOT_RUN })
    }

    @Test fun runnerDetectsUnexpectedWorkspaceModification() {
        val root = Files.createTempDirectory("aimaster-runner-fail").toFile()
        val store = ProjectWorkspaceStore(root)
        store.importZip(ByteArrayInputStream(zip("README.md" to "before\n")), "fixture.zip")
        val patch = ProjectPatchEngine.preview(
            listOf(ProjectPatchEngine.Proposal("README.md", store.readTextFile("README.md").sha256,
                "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-before\n+after\n")),
            store.listFiles()
        ) { path, limit -> store.readTextFile(path, limit) }
        val applied = store.applyPreview(patch)
        java.io.File(root, "current/README.md").writeText("tampered\n")
        val result = ProjectTestRunner(store).verifyAppliedChange(applied.snapshot.id, applied.changedPaths)
        assertEquals(ProjectTestRunner.Status.FAIL, result.status)
        assertTrue(result.checks.any { it.name == "WORKSPACE_INTEGRITY" && it.status == ProjectTestRunner.Status.FAIL })
        assertEquals(VerificationAssessmentStatus.FAIL, result.evidenceAssessment.status)
    }

    @Test fun runnerRejectsUnsafeOrDuplicatedChangeSetPaths() {
        val root = Files.createTempDirectory("aimaster-runner-paths").toFile()
        val store = ProjectWorkspaceStore(root)
        store.importZip(ByteArrayInputStream(zip("README.md" to "before\n")), "fixture.zip")
        val patch = ProjectPatchEngine.preview(
            listOf(ProjectPatchEngine.Proposal("README.md", store.readTextFile("README.md").sha256,
                "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-before\n+after\n")),
            store.listFiles()
        ) { path, limit -> store.readTextFile(path, limit) }
        val applied = store.applyPreview(patch)
        val result = ProjectTestRunner(store).verifyAppliedChange(applied.snapshot.id, listOf("README.md", "../outside"))
        assertEquals(ProjectTestRunner.Status.FAIL, result.status)
        assertTrue(result.checks.any { it.name == "PATCH_SCOPE" && it.status == ProjectTestRunner.Status.FAIL })
        assertFalse(result.projectCodeExecuted)
    }

    @Test fun runnerAcquiresWorkspaceGateWhenCalledOutsideMainActivity() {
        val root = Files.createTempDirectory("aimaster-runner-lock").toFile()
        val store = ProjectWorkspaceStore(root)
        store.importZip(ByteArrayInputStream(zip("README.md" to "before\n")), "fixture.zip")
        val patch = ProjectPatchEngine.preview(
            listOf(ProjectPatchEngine.Proposal("README.md", store.readTextFile("README.md").sha256,
                "--- a/README.md\n+++ b/README.md\n@@ -1 +1 @@\n-before\n+after\n")),
            store.listFiles()
        ) { path, limit -> store.readTextFile(path, limit) }
        val applied = store.applyPreview(patch)
        val ownerEntered = java.util.concurrent.CountDownLatch(1)
        val releaseOwner = java.util.concurrent.CountDownLatch(1)
        val runnerStarted = java.util.concurrent.CountDownLatch(1)
        val runnerFinished = java.util.concurrent.CountDownLatch(1)
        val owner = Thread {
            ProjectWorkspaceOperationLock.withLock {
                ownerEntered.countDown()
                check(releaseOwner.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        owner.start()
        assertTrue("lock owner did not enter", ownerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val runner = Thread {
            runnerStarted.countDown()
            ProjectTestRunner(store).verifyAppliedChange(applied.snapshot.id, applied.changedPaths)
            runnerFinished.countDown()
        }
        runner.start()
        assertTrue("runner did not start", runnerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse("runner read workspace without acquiring the global gate",
            runnerFinished.await(150, java.util.concurrent.TimeUnit.MILLISECONDS))
        releaseOwner.countDown()
        owner.join(5_000)
        runner.join(5_000)
        assertFalse("lock owner did not finish", owner.isAlive)
        assertFalse("runner did not finish after gate release", runner.isAlive)
        assertEquals(0L, runnerFinished.count)
    }

}
