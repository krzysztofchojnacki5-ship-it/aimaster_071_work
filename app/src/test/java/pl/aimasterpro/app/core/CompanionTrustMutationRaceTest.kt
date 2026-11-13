package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic registry-level race tests for the fences used by promote/revoke/wipe. */
class CompanionTrustMutationRaceTest {
    private class Handle(private val failClose: Boolean = false) : Closeable {
        val closes = AtomicInteger()
        override fun close() {
            closes.incrementAndGet()
            if (failClose) throw IllegalStateException("injected socket close failure")
        }
    }

    @Test fun peerRevocationRacingGlobalWipeCannotResurrectAnExistingTicket() {
        repeat(100) {
            val registry = CompanionConnectionRegistry()
            val stale = registry.ticket("peer-a")
            val handle = Handle()
            assertTrue(registry.register(stale, handle))
            val peerBatchReady = CountDownLatch(1)
            val releasePeerBatch = CountDownLatch(1)
            val startWipe = CountDownLatch(1)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val peer = workers.submit {
                    val batch = registry.beginRevocation("peer-a")
                    peerBatchReady.countDown()
                    assertTrue(releasePeerBatch.await(2, TimeUnit.SECONDS))
                    batch.close()
                }
                assertTrue(peerBatchReady.await(2, TimeUnit.SECONDS))
                val wipe = workers.submit {
                    assertTrue(startWipe.await(2, TimeUnit.SECONDS))
                    registry.beginRevocation(null).close()
                }
                startWipe.countDown()
                wipe.get(2, TimeUnit.SECONDS)
                releasePeerBatch.countDown()
                peer.get(2, TimeUnit.SECONDS)
                assertEquals(0, registry.liveCount())
                assertEquals(1, handle.closes.get())
                val rejected = Handle()
                assertFalse(registry.register(stale, rejected))
                assertEquals(1, rejected.closes.get())
            } finally {
                releasePeerBatch.countDown()
                workers.shutdownNow()
                registry.shutdown()
            }
        }
    }

    @Test fun duplicateGlobalWipesAndConcurrentBatchCloseRemainIdempotent() {
        repeat(100) {
            val registry = CompanionConnectionRegistry()
            val handle = Handle()
            assertTrue(registry.register(registry.ticket("peer-b"), handle))
            val first = registry.beginRevocation(null)
            val second = registry.beginRevocation(null)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val a = workers.submit { first.close() }
                val b = workers.submit { first.close(); second.close() }
                a.get(2, TimeUnit.SECONDS)
                b.get(2, TimeUnit.SECONDS)
                assertEquals(0, registry.liveCount())
                assertEquals(1, handle.closes.get())
            } finally {
                workers.shutdownNow()
                registry.shutdown()
            }
        }
    }

    @Test fun failedSocketCleanupDoesNotRestoreTrustOrRegistryOwnership() {
        val registry = CompanionConnectionRegistry()
        val handle = Handle(failClose = true)
        assertTrue(registry.register(registry.ticket("peer-c"), handle))
        // Ordinary close exceptions are isolated by the registry after ownership is detached.
        registry.beginRevocation("peer-c").close()
        assertEquals(1, handle.closes.get())
        assertEquals(0, registry.liveCount())
        val fresh = Handle()
        assertTrue(registry.register(registry.ticket("peer-c"), fresh))
        assertEquals(1, registry.liveCount())
        registry.shutdown()
        assertEquals(1, fresh.closes.get())
    }
}
