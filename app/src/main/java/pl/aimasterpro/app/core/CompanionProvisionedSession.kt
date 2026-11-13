package pl.aimasterpro.app.core

import java.io.Closeable

/**
 * A provisioning-time capability that keeps one pinned TLS profile inseparably paired with the
 * session owner constructed from that exact profile. Callers receive no independent profile or
 * owner reference, preventing accidental cross-wiring at the TLS admission boundary.
 *
 * The gate consumes this object for admission. The returned OwnedConnection, not this provision,
 * owns the live socket and is closed by registry revocation or explicit connection cleanup.
 */
class CompanionProvisionedSession internal constructor(
    internal val tlsProfile: CompanionPinnedTlsProfile,
    private val sessionOwner: CompanionSessionOwner
) : Closeable {
    init {
        require(sessionOwner.isBoundToProfile(tlsProfile)) {
            "Profil TLS i owner sesji Companion muszą pochodzić z tego samego provisioningu."
        }
    }

    fun acceptPairingReceipt(receipt: CompanionPairingReceipt): CompanionSessionOwner.Result =
        sessionOwner.acceptPairingReceipt(receipt)

    fun state(): CompanionSessionOwner.State = sessionOwner.state()

    internal fun ownerForAdmission(): CompanionSessionOwner = sessionOwner

    /** Closes the session owner and its exact TLS profile; safe to call repeatedly. */
    override fun close() {
        try {
            sessionOwner.close()
        } finally {
            tlsProfile.close()
        }
    }
}
