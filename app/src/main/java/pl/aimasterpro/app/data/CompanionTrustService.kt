package pl.aimasterpro.app.data

import android.content.Context
import pl.aimasterpro.app.core.CompanionPskRepository
import pl.aimasterpro.app.core.CompanionTrustRecovery

/**
 * Single entry point the UI uses for Companion trust. It only reads state and runs the explicit
 * recovery; it never pairs, never restores old trust, and never opens a network connection.
 * NOT RUN: requires a device.
 */
class CompanionTrustService(
    context: Context,
    private val provisioning: CompanionProvisioningStore
) {
    private val appContext = context.applicationContext
    private val keys = AndroidTlsKeyProvider()
    private val chainBlob = AndroidTlsChainBlobStore(appContext)
    private val pskBlob = AndroidPskCipher.blobStore(appContext)
    private val psk = CompanionPskRepository(pskBlob, AndroidPskCipher())
    private val targets = AndroidTrustRecoveryTargets(provisioning, chainBlob, pskBlob, keys)

    fun state(): CompanionTrustRecovery.State = CompanionTrustRecovery.evaluate(targets.snapshot(psk))

    fun recover(confirmation: String): CompanionTrustRecovery.WipeResult =
        CompanionTrustRecovery.recover(confirmation, targets)
}
