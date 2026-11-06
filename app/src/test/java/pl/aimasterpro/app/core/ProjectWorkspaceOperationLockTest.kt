package pl.aimasterpro.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProjectWorkspaceOperationLockTest {
    @Test fun serializesWorkspaceOperationsAcrossDifferentStoreCallers() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val first = Thread {
            ProjectWorkspaceOperationLock.withLock {
                firstEntered.countDown()
                check(releaseFirst.await(2, TimeUnit.SECONDS))
            }
        }
        val second = Thread {
            ProjectWorkspaceOperationLock.withLock { secondEntered.countDown() }
        }
        first.start()
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        second.start()
        try {
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
        }
        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        first.join(1_000)
        second.join(1_000)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }

    @Test fun lockIsReentrantForNestedWorkspaceHelpers() {
        val value = ProjectWorkspaceOperationLock.withLock {
            ProjectWorkspaceOperationLock.withLock { "nested-ok" }
        }
        assertTrue(value == "nested-ok")
    }
    @Test fun nonBlockingProbeReturnsNullWhileBackgroundOperationOwnsLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            ProjectWorkspaceOperationLock.withLock {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
        }
        worker.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            assertTrue(ProjectWorkspaceOperationLock.tryWithLock { "unexpected" } == null)
        } finally {
            release.countDown()
        }
        worker.join(1_000)
        assertFalse(worker.isAlive)
    }

    @Test fun exceptionReleasesWorkspaceLockForNextOperation() {
        runCatching {
            ProjectWorkspaceOperationLock.withLock { error("expected") }
        }
        val entered = ProjectWorkspaceOperationLock.tryWithLock { true }
        assertTrue(entered == true)
    }

    @Test fun destructiveOperationRequiresExactWorkspaceIdentity() {
        ProjectWorkspaceIdentityGuard.requireUnchanged("workspace-a", "workspace-a")
    }

    @Test fun destructiveOperationRejectsReplacedWorkspace() {
        val failure = runCatching {
            ProjectWorkspaceIdentityGuard.requireUnchanged("workspace-a", "workspace-b")
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun destructiveOperationRejectsMissingWorkspaceIdentity() {
        val failure = runCatching {
            ProjectWorkspaceIdentityGuard.requireUnchanged("workspace-a", null)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun importInvalidationIsAtomicAgainstNewPatchActivation() {
        val workspace = java.util.concurrent.atomic.AtomicReference("old-workspace")
        val activePointer = java.util.concurrent.atomic.AtomicReference("old-change")
        val importEntered = CountDownLatch(1)
        val finishImport = CountDownLatch(1)
        val importThread = Thread {
            ProjectWorkspaceOperationLock.withLock {
                workspace.set("new-workspace")
                importEntered.countDown()
                check(finishImport.await(2, TimeUnit.SECONDS))
                activePointer.set("") // invalidation is part of the same critical section
            }
        }
        val activationThread = Thread {
            check(importEntered.await(1, TimeUnit.SECONDS))
            ProjectWorkspaceOperationLock.withLock {
                if (workspace.get() == "new-workspace") activePointer.set("new-change")
            }
        }
        importThread.start()
        assertTrue(importEntered.await(1, TimeUnit.SECONDS))
        activationThread.start()
        try {
            Thread.sleep(50)
            assertTrue(activationThread.isAlive)
        } finally {
            finishImport.countDown()
        }
        importThread.join(1_000)
        activationThread.join(1_000)
        assertFalse(importThread.isAlive)
        assertFalse(activationThread.isAlive)
        assertTrue(activePointer.get() == "new-change")
    }

}
