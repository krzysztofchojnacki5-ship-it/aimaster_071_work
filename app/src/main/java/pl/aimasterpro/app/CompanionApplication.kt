package pl.aimasterpro.app

import android.app.Application
import pl.aimasterpro.app.data.CompanionProvisioningStore
import pl.aimasterpro.app.data.CompanionTrustService
import pl.aimasterpro.app.core.CompanionRuntime
import pl.aimasterpro.app.core.CompanionRuntimeSlot
import pl.aimasterpro.app.core.CompanionTlsHandshakeGate

/**
 * Process owner for durable Companion trust state and the in-process admission/revocation graph.
 * The durable store and runtime are each created lazily exactly once per Application instance.
 * The runtime owns only an in-memory registry and a revocation listener; it never restores
 * sockets, admission tickets, or sessions after process recreation.
 */
class CompanionApplication : Application() {
    val companionProvisioningStore: CompanionProvisioningStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CompanionProvisioningStore(applicationContext)
    }

    private val companionRuntimeSlot = CompanionRuntimeSlot()

    fun companionRuntime(): CompanionRuntime =
        companionRuntimeSlot.getOrCreate(companionProvisioningStore)

    fun companionTlsHandshakeGate(): CompanionTlsHandshakeGate = companionRuntime().tlsHandshakeGate

    fun companionTrustService(): CompanionTrustService =
        CompanionTrustService(applicationContext, companionProvisioningStore)
}
