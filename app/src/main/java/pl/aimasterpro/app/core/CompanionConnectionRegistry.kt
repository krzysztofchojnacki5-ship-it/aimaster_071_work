package pl.aimasterpro.app.core

import java.io.Closeable
import java.util.Collections
import java.util.IdentityHashMap

/** Optional handle contract: registry revocation is distinct from ordinary caller close. */
interface CompanionRevocationAwareConnection : Closeable {
    fun closeForRevocation()
}

/**
 * In-memory registry for live Companion connection handles, scoped to one application runtime.
 *
 * Tracks live connection handles per authenticated peer. A caller captures a [Ticket] before
 * starting TLS handshake; registration after any intervening peer/global revocation is rejected.
 *
 * Ownership is identity-based, not equals()-based: a Closeable instance can belong to at most one
 * peer and is closed at most once by this registry. Re-registering the same instance for the same
 * peer is idempotent. Attempting to transfer an already-owned instance to another peer fails
 * closed: the existing ownership is removed and the handle is closed outside the lock.
 */
class CompanionConnectionRegistry {
    private val lock = Any()
    private var globalEpoch = 0L
    private var closed = false
    private val peerEpoch = HashMap<String, Long>()
    private val live = HashMap<String, MutableSet<Closeable>>()
    private val owners = IdentityHashMap<Closeable, String>()
    // Detached handles stay reserved until their out-of-lock close attempt has finished.
    // This prevents a concurrent fresh ticket from re-owning an object that an older revoke
    // is about to close outside the registry lock.
    private val closing = Collections.newSetFromMap(IdentityHashMap<Closeable, Boolean>())
    // Tickets are identity-tracked and single-use; only in-flight handshakes occupy this set.
    private val outstandingTickets = Collections.newSetFromMap(IdentityHashMap<Ticket, Boolean>())

    class Ticket internal constructor(internal val peerId: String, internal val global: Long, internal val peer: Long)

    fun ticket(peerId: String): Ticket {
        validatePeerId(peerId)
        return synchronized(lock) {
            check(!closed) { "Registry Companion jest zamknięty." }
            check(outstandingTickets.size < MAX_PENDING_TICKETS) {
                "Limit równoległych admission handshake Companion został osiągnięty."
            }
            Ticket(peerId, globalEpoch, peerEpoch[peerId] ?: 0L).also(outstandingTickets::add)
        }
    }

    /** Cancels an in-flight ticket when TLS handshake/owner activation fails before register(). */
    fun cancel(ticket: Ticket) {
        synchronized(lock) {
            if (outstandingTickets.remove(ticket)) prunePeerEpochIfIdle(ticket.peerId)
        }
    }

    /**
     * Returns true if the handle is owned by this peer after the call.
     * Tickets are identity-based and single-use. Unknown, reused, stale or cross-peer attempts
     * return false; a new unowned handle is closed, while an already-owned handle is never
     * allowed to be evicted by an invalid ticket. Callers must cancel tickets on handshake failure.
     */
    fun register(ticket: Ticket, connection: Closeable): Boolean {
        validatePeerId(ticket.peerId)
        val decision = synchronized(lock) {
            val ticketKnown = outstandingTickets.remove(ticket)
            val ticketCurrent = ticketKnown && !closed && ticket.global == globalEpoch &&
                ticket.peer == (peerEpoch[ticket.peerId] ?: 0L)
            val existingOwner = owners[connection]
            val result = when {
                // A detached handle cannot be re-admitted while its previous close is in flight.
                connection in closing -> false to false
                // An invalid/stale ticket must never be able to evict a handle already owned
                // by another peer (or interfere with an idempotent registration).
                !ticketCurrent && existingOwner != null -> false to false
                existingOwner != null && existingOwner != ticket.peerId -> {
                    detachOwnedHandle(existingOwner, connection)
                    false to true // Valid but conflicting ownership: fail closed.
                }
                existingOwner == ticket.peerId -> true to false // Idempotent duplicate.
                !ticketCurrent -> {
                    closing.add(connection)
                    false to true
                }
                else -> {
                    live.getOrPut(ticket.peerId) { identitySet() }.add(connection)
                    owners[connection] = ticket.peerId
                    true to false
                }
            }
            if (!ticketCurrent) prunePeerEpochIfIdle(ticket.peerId)
            result
        }
        if (decision.second) closeReserved(connection)
        return decision.first
    }

    /** Removes a normally closed handle. A mismatched peer cannot release another peer's handle. */
    fun release(peerId: String, connection: Closeable) {
        validatePeerId(peerId)
        synchronized(lock) {
            if (owners[connection] != peerId) return
            owners.remove(connection)
            live[peerId]?.let { owned ->
                owned.remove(connection)
                if (owned.isEmpty()) live.remove(peerId)
            }
            prunePeerEpochIfIdle(peerId)
        }
    }

    /**
     * A two-phase revocation result. Creating it advances the epoch and detaches handles
     * synchronously under the registry lock; close() performs potentially blocking I/O later.
     * close() is idempotent so transaction cleanup can safely run from multiple failure paths.
     */
    inner class RevocationBatch internal constructor(private val detached: List<Closeable>) : Closeable {
        private val completionLock = Any()
        private var completed = false

        val handleCount: Int get() = detached.size

        override fun close() {
            synchronized(completionLock) {
                if (completed) return
                completed = true
                // Keep concurrent close() callers waiting until every detached handle was tried.
                // This lock is independent of the registry lock and is never held during admission.
                closeAllReserved(detached)
            }
        }
    }

    /**
     * Phase one: advance the peer/global epoch and detach all affected handles without closing
     * sockets. The caller MUST invoke the returned batch's close() after releasing any external
     * store/owner monitor. A stale handshake ticket is rejected immediately after this returns.
     */
    fun beginRevocation(peerId: String?): RevocationBatch {
        if (peerId != null) validatePeerId(peerId)
        val toClose: List<Closeable> = synchronized(lock) {
            if (closed) return@synchronized emptyList()
            if (peerId == null) {
                globalEpoch = nextEpoch(globalEpoch)
                val all = owners.keys.toList()
                owners.clear()
                live.clear()
                closing.addAll(all)
                all
            } else {
                peerEpoch[peerId] = nextEpoch(peerEpoch[peerId] ?: 0L)
                val removed = live.remove(peerId)?.toList() ?: emptyList()
                removed.forEach {
                    owners.remove(it)
                    closing.add(it)
                }
                prunePeerEpochIfIdle(peerId)
                removed
            }
        }
        return RevocationBatch(toClose)
    }

    /**
     * Permanently shuts this process-local registry down. Outstanding admission tickets are
     * invalidated and all live handles are detached before socket cleanup begins. Runtime.close()
     * serializes callers, so this internal lifecycle operation has one owner.
     */
    internal fun shutdown() {
        val detached = synchronized(lock) {
            if (closed) return
            closed = true
            val all = owners.keys.toList()
            owners.clear()
            live.clear()
            outstandingTickets.clear()
            peerEpoch.clear()
            closing.addAll(all)
            all
        }
        closeAllReserved(detached)
    }

    /** Convenience operation for callers that do not hold an external transaction lock. */
    fun revoke(peerId: String?): Int {
        val batch = beginRevocation(peerId)
        batch.close()
        return batch.handleCount
    }

    /** Number of currently owned handle instances; diagnostics/tests only. */
    fun liveCount(): Int = synchronized(lock) { owners.size }

    private fun detachOwnedHandle(peerId: String, connection: Closeable) {
        owners.remove(connection)
        closing.add(connection)
        live[peerId]?.let { owned ->
            owned.remove(connection)
            if (owned.isEmpty()) live.remove(peerId)
        }
        prunePeerEpochIfIdle(peerId)
    }

    /** Epoch tombstones are needed only while a peer has live handles or outstanding tickets. */
    private fun prunePeerEpochIfIdle(peerId: String) {
        if (live[peerId].isNullOrEmpty() && outstandingTickets.none { it.peerId == peerId }) {
            peerEpoch.remove(peerId)
        }
    }

    /** Diagnostic count used by bounded-growth tests. */
    fun peerEpochCount(): Int = synchronized(lock) { peerEpoch.size }

    private fun closeReserved(connection: Closeable) {
        try {
            closeHandle(connection)
        } catch (_: Exception) {
            // The handle was detached before close; a broken close cannot retain registry ownership.
        } finally {
            synchronized(lock) { closing.remove(connection) }
        }
    }

    /** Attempt every close even if one handle throws an Error; rethrow the first fatal Error after. */
    private fun closeAllReserved(connections: List<Closeable>) {
        var fatal: Error? = null
        connections.forEach { connection ->
            try {
                closeHandle(connection)
            } catch (_: Exception) {
                // Continue: one broken close must not strand other detached handles.
            } catch (error: Error) {
                if (fatal == null) fatal = error
            } finally {
                synchronized(lock) { closing.remove(connection) }
            }
        }
        fatal?.let { throw it }
    }

    private fun closeHandle(connection: Closeable) {
        if (connection is CompanionRevocationAwareConnection) connection.closeForRevocation()
        else connection.close()
    }

    private fun validatePeerId(peerId: String) {
        require(peerId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Nieprawidłowy identyfikator peera." }
    }

    private fun nextEpoch(value: Long): Long {
        check(value != Long.MAX_VALUE) { "Wyczerpano licznik epok unieważnienia; wymagane odtworzenie rejestru." }
        return value + 1L
    }

    private fun identitySet(): MutableSet<Closeable> =
        Collections.newSetFromMap(IdentityHashMap<Closeable, Boolean>())

    private companion object {
        // Match the TLS profile's bound on unconsumed peer receipts.
        const val MAX_PENDING_TICKETS = 32
    }
}
