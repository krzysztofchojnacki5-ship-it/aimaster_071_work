package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Regression tests for admission/revocation races in the production in-memory registry. */
class CompanionTrustRotationConcurrencyTest {
    private class TrackedConnection : Closeable {
        val closeCount = AtomicInteger()
        override fun close() { closeCount.incrementAndGet() }
    }

    @Test fun registrationRacingPeerRevocationNeverLeavesAResurrectedHandle() {
        repeat(100) {
            val registry = CompanionConnectionRegistry()
            val ticket = registry.ticket("desktop")
            val connection = TrackedConnection()
            val start = CountDownLatch(1)
            val registerDone = CountDownLatch(1)
            val revokeDone = CountDownLatch(1)

            val registerThread = Thread {
                start.await()
                try { registry.register(ticket, connection) } finally { registerDone.countDown() }
            }
            val revokeThread = Thread {
                start.await()
                try { registry.revoke("desktop") } finally { revokeDone.countDown() }
            }
            registerThread.start()
            revokeThread.start()
            start.countDown()

            assertTrue(registerDone.await(2, TimeUnit.SECONDS))
            assertTrue(revokeDone.await(2, TimeUnit.SECONDS))
            assertEquals(0, registry.liveCount())
            assertEquals(1, connection.closeCount.get())
            registry.shutdown()
        }
    }

    @Test fun globalWipeFencesEveryPreviouslyIssuedTicketAcrossConcurrentAdmissions() {
        val registry = CompanionConnectionRegistry()
        val tickets = (0 until 24).map { registry.ticket("peer$it") }
        val connections = tickets.map { TrackedConnection() }
        val batch = registry.beginRevocation(null)
        val start = CountDownLatch(1)
        val done = CountDownLatch(tickets.size)
        tickets.indices.forEach { index ->
            Thread {
                start.await()
                try { assertFalse(registry.register(tickets[index], connections[index])) }
                finally { done.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        batch.close()
        assertEquals(0, registry.liveCount())
        connections.forEach { assertEquals(1, it.closeCount.get()) }
        registry.shutdown()
    }

    @Test fun peerRevocationDoesNotInvalidateAnotherPeersFreshAdmission() {
        val registry = CompanionConnectionRegistry()
        val stale = registry.ticket("desktop")
        registry.revoke("desktop")
        val freshOtherPeer = TrackedConnection()
        assertTrue(registry.register(registry.ticket("mobile"), freshOtherPeer))
        val staleConnection = TrackedConnection()
        assertFalse(registry.register(stale, staleConnection))
        assertEquals(1, staleConnection.closeCount.get())
        assertEquals(1, registry.liveCount())
        registry.revoke(null)
        assertEquals(1, freshOtherPeer.closeCount.get())
    }
}
