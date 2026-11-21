package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProviderCatalogSyncGenerationTest {
    @Test
    fun invalidatedSnapshotCannotCommit() {
        val gate = ProviderCatalogSyncGeneration()
        val snapshot = gate.snapshot()
        gate.invalidate()
        var writes = 0

        val committed = gate.commitIfCurrent(snapshot) { writes++ }

        assertFalse(committed)
        assertEquals(0, writes)
    }

    @Test
    fun currentSnapshotCanCommitExactlyOncePerInvocation() {
        val gate = ProviderCatalogSyncGeneration()
        val snapshot = gate.snapshot()
        var writes = 0

        val first = gate.commitIfCurrent(snapshot) { writes++ }
        val second = gate.commitIfCurrent(snapshot) { writes++ }

        assertTrue(first)
        assertTrue(second)
        assertEquals(2, writes)
    }

    @Test
    fun invalidationProducesANewCurrentSnapshot() {
        val gate = ProviderCatalogSyncGeneration()
        val oldSnapshot = gate.snapshot()
        val newSnapshot = gate.invalidate()

        assertFalse(gate.isCurrent(oldSnapshot))
        assertTrue(gate.isCurrent(newSnapshot))
    }

    @Test
    fun snapshotCannotObserveGenerationUntilProviderSwapActionCompletes() {
        val gate = ProviderCatalogSyncGeneration()
        val enteredSwap = CountDownLatch(1)
        val releaseSwap = CountDownLatch(1)
        val snapshotFinished = CountDownLatch(1)
        val snapshotResult = AtomicReference<Long>()

        val swapThread = Thread {
            gate.invalidateAndRun {
                enteredSwap.countDown()
                check(releaseSwap.await(2, TimeUnit.SECONDS))
            }
        }
        swapThread.start()
        assertTrue(enteredSwap.await(2, TimeUnit.SECONDS))

        val snapshotThread = Thread {
            snapshotResult.set(gate.snapshot())
            snapshotFinished.countDown()
        }
        snapshotThread.start()

        assertFalse(snapshotFinished.await(100, TimeUnit.MILLISECONDS))
        releaseSwap.countDown()
        assertTrue(snapshotFinished.await(2, TimeUnit.SECONDS))
        swapThread.join(2_000)
        snapshotThread.join(2_000)

        assertEquals(1L, snapshotResult.get())
    }
}
