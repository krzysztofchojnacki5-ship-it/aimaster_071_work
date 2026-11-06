package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** JUnit coverage for the production CompanionConnectionRegistry. */
class CompanionConnectionRegistryTest {
    private open class Conn(var failOnClose: Boolean = false) : Closeable {
        @Volatile var closed = 0
        override fun close() {
            closed++
            if (failOnClose) throw IllegalStateException("close failed")
        }
    }

    /** Deliberately makes distinct instances compare equal; registry ownership must use identity. */
    private class EqualConn : Conn() {
        override fun equals(other: Any?): Boolean = other is EqualConn
        override fun hashCode(): Int = 1
    }

    @Test fun revokeClosesRegisteredConnectionOfThatPeer() {
        val r = CompanionConnectionRegistry()
        val c = Conn()
        assertTrue(r.register(r.ticket("desktop"), c))
        assertEquals(1, r.revoke("desktop"))
        assertEquals(1, c.closed)
        assertEquals(0, r.liveCount())
    }

    @Test fun beginRevocationFencesAdmissionBeforeDeferredSocketClose() {
        val r = CompanionConnectionRegistry()
        val live = Conn()
        assertTrue(r.register(r.ticket("desktop"), live))
        val inFlight = r.ticket("desktop")

        val batch = r.beginRevocation("desktop")
        assertEquals(1, batch.handleCount)
        assertEquals("phase one must not perform socket I/O", 0, live.closed)
        assertEquals(0, r.liveCount())

        val late = Conn()
        assertFalse(r.register(inFlight, late))
        assertEquals(1, late.closed)
        assertEquals("detached handle closes only in phase two", 0, live.closed)

        batch.close()
        batch.close() // completion is idempotent
        assertEquals(1, live.closed)
    }

    @Test fun revokeDoesNotTouchOtherPeers() {
        val r = CompanionConnectionRegistry()
        val a = Conn(); val b = Conn()
        r.register(r.ticket("desktop"), a)
        r.register(r.ticket("other"), b)
        r.revoke("desktop")
        assertEquals(1, a.closed)
        assertEquals(0, b.closed)
        assertEquals(1, r.liveCount())
    }

    @Test fun ticketTakenBeforeRevocationIsStaleAndConnectionIsClosed() {
        val r = CompanionConnectionRegistry()
        val ticket = r.ticket("desktop")
        r.revoke("desktop")
        val late = Conn()
        assertFalse(r.register(ticket, late))
        assertEquals(1, late.closed)
    }

    @Test fun ticketMustBeCapturedBeforeHandshakeAndStaleHandshakeIsClosed() {
        val r = CompanionConnectionRegistry()
        val ticket = r.ticket("desktop") // Same ordering required by CompanionTlsHandshakeGate.
        val socket = Conn()
        // Simulate a trust revocation while the TLS handshake is in progress.
        r.revoke("desktop")
        assertFalse(r.register(ticket, socket))
        assertEquals(1, socket.closed)
        assertEquals(0, r.liveCount())
    }

    private class RevocationAwareConn : Conn(), CompanionRevocationAwareConnection {
        var revoked = 0
        override fun closeForRevocation() { revoked++; close() }
    }

    @Test fun revocationUsesDedicatedCallbackForOwnedConnection() {
        val r = CompanionConnectionRegistry()
        val c = RevocationAwareConn()
        assertTrue(r.register(r.ticket("desktop"), c))
        assertEquals(1, r.revoke("desktop"))
        assertEquals(1, c.revoked)
        assertEquals(1, c.closed)
    }

    @Test fun stalePreHandshakeTicketUsesRevocationCallback() {
        val r = CompanionConnectionRegistry()
        val ticket = r.ticket("desktop")
        r.revoke("desktop")
        val c = RevocationAwareConn()
        assertFalse(r.register(ticket, c))
        assertEquals(1, c.revoked)
        assertEquals(1, c.closed)
    }

    @Test fun globalRevocationStalesEveryTicketAndClosesAllPeers() {
        val r = CompanionConnectionRegistry()
        val ticket = r.ticket("desktop")
        val a = Conn(); val b = Conn()
        r.register(r.ticket("desktop"), a)
        r.register(r.ticket("other"), b)
        assertEquals(2, r.revoke(null))
        assertEquals(1, a.closed)
        assertEquals(1, b.closed)
        val late = Conn()
        assertFalse(r.register(ticket, late))
        assertEquals(1, late.closed)
        assertEquals(0, r.liveCount())
    }

    @Test fun duplicateRegistrationForSamePeerIsIdempotent() {
        val r = CompanionConnectionRegistry()
        val c = Conn()
        assertTrue(r.register(r.ticket("desktop"), c))
        assertTrue(r.register(r.ticket("desktop"), c)) // Fresh ticket; same handle is already owned.
        assertEquals(1, r.liveCount())
        assertEquals(1, r.revoke("desktop"))
        assertEquals(1, c.closed)
    }

    @Test fun crossPeerRegistrationFailsClosedWithoutLeavingStaleOwnership() {
        val r = CompanionConnectionRegistry()
        val c = Conn()
        assertTrue(r.register(r.ticket("desktop"), c))
        assertFalse(r.register(r.ticket("other"), c))
        assertEquals(1, c.closed)
        assertEquals(0, r.liveCount())
        assertEquals(0, r.revoke("desktop"))
    }

    @Test fun equalButDistinctHandlesAreTrackedIndependently() {
        val r = CompanionConnectionRegistry()
        val a = EqualConn(); val b = EqualConn()
        assertTrue(r.register(r.ticket("desktop"), a))
        assertTrue(r.register(r.ticket("desktop"), b))
        assertEquals(2, r.liveCount())
        assertEquals(2, r.revoke("desktop"))
        assertEquals(1, a.closed)
        assertEquals(1, b.closed)
    }

    @Test fun releaseByWrongPeerCannotDetachHandle() {
        val r = CompanionConnectionRegistry()
        val c = Conn()
        assertTrue(r.register(r.ticket("desktop"), c))
        r.release("other", c)
        assertEquals(1, r.revoke("desktop"))
        assertEquals(1, c.closed)
    }

    @Test fun doubleRevokeIsSafe() {
        val r = CompanionConnectionRegistry()
        val c = Conn()
        r.register(r.ticket("desktop"), c)
        r.revoke("desktop")
        assertEquals(0, r.revoke("desktop"))
        assertEquals(1, c.closed)
    }

    @Test fun failingCloseDoesNotPreventClosingOtherHandles() {
        val r = CompanionConnectionRegistry()
        val bad = Conn(failOnClose = true)
        val good = Conn()
        r.register(r.ticket("desktop"), bad)
        r.register(r.ticket("desktop"), good)
        assertEquals(2, r.revoke("desktop"))
        assertEquals(1, bad.closed)
        assertEquals(1, good.closed)
    }

    @Test fun detachedHandleCannotBeReadmittedWhileRevocationCloseIsInFlight() {
        val r = CompanionConnectionRegistry()
        val entered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val c = object : Conn() {
            override fun close() {
                closed++
                entered.countDown()
                check(allowClose.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(r.register(r.ticket("desktop"), c))
        val revokeThread = Thread { r.revoke("desktop") }
        revokeThread.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        // A new ticket cannot re-own the same identity while the old revoke is closing it.
        assertFalse(r.register(r.ticket("desktop"), c))
        assertEquals(0, r.liveCount())
        allowClose.countDown()
        revokeThread.join(2_000)
        assertFalse(revokeThread.isAlive)
        assertEquals(1, c.closed)
        assertEquals(0, r.liveCount())
    }

    @Test fun fatalCloseErrorDoesNotPreventRemainingCloseAttempts() {
        val r = CompanionConnectionRegistry()
        val fatal = object : Conn() {
            override fun close() {
                closed++
                throw AssertionError("fatal close")
            }
        }
        val good = Conn()
        assertTrue(r.register(r.ticket("desktop"), fatal))
        assertTrue(r.register(r.ticket("desktop"), good))
        try {
            r.revoke("desktop")
            throw AssertionError("Expected fatal close error")
        } catch (expected: AssertionError) {
            assertEquals("fatal close", expected.message)
        }
        assertEquals(1, fatal.closed)
        assertEquals(1, good.closed)
        assertEquals(0, r.liveCount())
    }


    private class FailingRevocationConn : Conn(), CompanionRevocationAwareConnection {
        override fun closeForRevocation() {
            revoked++
            throw IllegalStateException("revocation callback failed")
        }
        var revoked = 0
    }

    @Test fun failingRevocationCallbackDoesNotPreventClosingOtherHandles() {
        val r = CompanionConnectionRegistry()
        val bad = FailingRevocationConn()
        val good = RevocationAwareConn()
        r.register(r.ticket("desktop"), bad)
        r.register(r.ticket("desktop"), good)
        assertEquals(2, r.revoke("desktop"))
        assertEquals(1, bad.revoked)
        assertEquals(0, bad.closed)
        assertEquals(1, good.revoked)
        assertEquals(1, good.closed)
        assertEquals(0, r.liveCount())
    }

    @Test fun releasedConnectionIsNotClosedByRevoke() {
        val r = CompanionConnectionRegistry()
        val c = Conn()
        r.register(r.ticket("desktop"), c)
        r.release("desktop", c)
        r.revoke("desktop")
        assertEquals(0, c.closed)
        assertEquals(0, r.liveCount())
    }

    @Test fun concurrentRegisterAndRevokeAlwaysCloseExactlyOnce() {
        repeat(200) { index ->
            val r = CompanionConnectionRegistry()
            val ticket = r.ticket("peer$index")
            val c = RevocationAwareConn()
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val register = Thread {
                try {
                    start.await()
                    r.register(ticket, c)
                } finally { done.countDown() }
            }
            val revoke = Thread {
                try {
                    start.await()
                    r.revoke("peer$index")
                } finally { done.countDown() }
            }
            register.start(); revoke.start(); start.countDown()
            assertTrue("threads timed out at iteration $index", done.await(2, TimeUnit.SECONDS))
            register.join(); revoke.join()
            assertEquals("connection must be closed exactly once at iteration $index", 1, c.closed)
            assertEquals("revocation callback must run exactly once at iteration $index", 1, c.revoked)
            assertEquals("registry must be empty at iteration $index", 0, r.liveCount())
        }
    }

    @Test fun ticketIsSingleUseAndCannotAdmitSecondHandle() {
        val r = CompanionConnectionRegistry()
        val ticket = r.ticket("desktop")
        val first = Conn()
        val replay = Conn()
        assertTrue(r.register(ticket, first))
        assertFalse(r.register(ticket, replay))
        assertEquals(1, replay.closed)
        assertEquals(1, r.liveCount())
        assertEquals(1, r.revoke("desktop"))
        assertEquals(1, first.closed)
    }

    @Test fun canceledTicketCannotBeReusedAndDoesNotBlockLaterTicket() {
        val r = CompanionConnectionRegistry()
        val canceled = r.ticket("desktop")
        r.cancel(canceled)
        val rejected = Conn()
        assertFalse(r.register(canceled, rejected))
        assertEquals(1, rejected.closed)
        val accepted = Conn()
        assertTrue(r.register(r.ticket("desktop"), accepted))
        assertEquals(1, r.revoke("desktop"))
        assertEquals(1, accepted.closed)
    }

    @Test fun pendingHandshakeTicketsAreBoundedAndCanceledSlotsAreReusable() {
        val r = CompanionConnectionRegistry()
        val tickets = (0 until 32).map { r.ticket("peer$it") }
        try {
            r.ticket("overflow")
            throw AssertionError("Expected pending admission limit")
        } catch (_: IllegalStateException) { }
        r.cancel(tickets.first())
        val replacement = r.ticket("replacement")
        r.cancel(replacement)
        tickets.drop(1).forEach(r::cancel)
    }


    @Test fun staleTicketCannotEvictHandleOwnedByAnotherPeer() {
        val r = CompanionConnectionRegistry()
        val live = Conn()
        assertTrue(r.register(r.ticket("desktop"), live))
        val staleOtherPeerTicket = r.ticket("other")
        r.revoke("other")
        assertFalse(r.register(staleOtherPeerTicket, live))
        assertEquals("stale ticket must not close another peer's handle", 0, live.closed)
        assertEquals(1, r.liveCount())
        assertEquals(1, r.revoke("desktop"))
        assertEquals(1, live.closed)
    }

    @Test fun peerEpochTombstonesArePrunedWhenNoTicketOrHandleRemains() {
        val r = CompanionConnectionRegistry()
        repeat(500) { index ->
            val peer = "ephemeral$index"
            r.ticket(peer).also { ticket ->
                r.revoke(peer)
                r.cancel(ticket)
            }
        }
        assertEquals(0, r.peerEpochCount())
    }

    @Test fun invalidTicketCannotDisturbSamePeerOwnedHandle() {
        val r = CompanionConnectionRegistry()
        val live = Conn()
        val ticket = r.ticket("desktop")
        assertTrue(r.register(ticket, live))
        assertFalse(r.register(ticket, live)) // Reused ticket is rejected; existing handle remains live.
        assertEquals(0, live.closed)
        assertEquals(1, r.liveCount())
        assertEquals(1, r.revoke("desktop"))
        assertEquals(1, live.closed)
    }

    @Test fun invalidPeerIdsAreRejected() {
        val r = CompanionConnectionRegistry()
        try {
            r.ticket("bad peer")
            throw AssertionError("Expected invalid peer ID rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun concurrentBatchCloseWaitsUntilDetachedHandleCleanupFinishes() {
        val r = CompanionConnectionRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handle = object : Closeable {
            override fun close() {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(r.register(r.ticket("desktop"), handle))
        val batch = r.beginRevocation("desktop")
        val first = Thread { batch.close() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val secondFinished = CountDownLatch(1)
        val second = Thread { batch.close(); secondFinished.countDown() }.apply { start() }
        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        first.join(2_000); second.join(2_000)
        assertFalse(first.isAlive); assertFalse(second.isAlive)
        assertTrue(secondFinished.count == 0L)
    }

}
