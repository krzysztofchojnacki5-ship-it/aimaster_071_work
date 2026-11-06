package pl.aimasterpro.app.data

import pl.aimasterpro.app.core.CompanionPskRepository
import pl.aimasterpro.app.core.CompanionTlsBlobStore
import pl.aimasterpro.app.core.CompanionTlsChainCodec
import pl.aimasterpro.app.core.CompanionTlsChainFormatException
import pl.aimasterpro.app.core.CompanionTrustRecovery

/**
 * Android adapter for explicit trust recovery: builds the evaluation snapshot from real storage and
 * performs the wipe steps. NOT RUN: requires a device. Neither method reads old trust back.
 */
class AndroidTrustRecoveryTargets(
    private val provisioning: CompanionProvisioningStore,
    private val chainBlob: CompanionTlsBlobStore,
    private val pskBlob: CompanionTlsBlobStore,
    private val keys: AndroidTlsKeyProvider
) : CompanionTrustRecovery.WipeTargets {

    fun snapshot(psk: CompanionPskRepository): CompanionTrustRecovery.Snapshot {
        val pins = try {
            provisioning.readPeerPins()
        } catch (_: Exception) {
            null
        }
        return CompanionTrustRecovery.Snapshot(
            pinsReadable = pins != null,
            pinsPresent = pins?.isNotEmpty() == true,
            psk = when (psk.wellFormed()) {
                null -> CompanionTrustRecovery.Status.ABSENT
                true -> CompanionTrustRecovery.Status.OK
                false -> CompanionTrustRecovery.Status.BAD
            },
            chain = chainStatus(),
            tlsKeyPresent = keys.hasAnyTlsKey()
        )
    }

    private fun chainStatus(): CompanionTrustRecovery.Status {
        val stored = chainBlob.read() ?: return CompanionTrustRecovery.Status.ABSENT
        return try {
            CompanionTlsChainCodec.decode(stored)
            CompanionTrustRecovery.Status.OK
        } catch (_: CompanionTlsChainFormatException) {
            CompanionTrustRecovery.Status.BAD
        }
    }

    override fun clearPins() = provisioning.wipeAllTrust()

    override fun clearPsk() {
        check(pskBlob.clear()) { "Nie udało się usunąć rekordu PSK." }
    }

    override fun clearChain() {
        check(chainBlob.clear()) { "Nie udało się usunąć łańcucha TLS." }
    }

    override fun deleteTlsKeys() = keys.deleteAllTlsKeys()
}
