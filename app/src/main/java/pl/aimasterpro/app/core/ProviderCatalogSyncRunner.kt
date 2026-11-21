package pl.aimasterpro.app.core

import java.util.concurrent.CancellationException

/**
 * Executes model-catalog discovery with a failure boundary per provider.
 *
 * Discovery and persistence are deliberately supplied as lambdas so the policy can
 * be tested without Android, network clients or API credentials.
 */
data class ProviderCatalogSyncFailure<P>(
    val provider: P,
    val message: String
)

data class ProviderCatalogSyncReport<P>(
    val successfulProviders: List<P>,
    val failures: List<ProviderCatalogSyncFailure<P>>,
    val stopped: Boolean
) {
    val successCount: Int get() = successfulProviders.size
}

object ProviderCatalogSyncRunner {
    fun <P, M> synchronize(
        providers: List<P>,
        isCancelled: () -> Boolean = { false },
        discover: (P) -> List<M>,
        save: (P, List<M>) -> Unit
    ): ProviderCatalogSyncReport<P> {
        val successes = mutableListOf<P>()
        val failures = mutableListOf<ProviderCatalogSyncFailure<P>>()
        var stopped = false

        fun shouldStop(): Boolean = Thread.currentThread().isInterrupted || isCancelled()

        for (provider in providers) {
            if (shouldStop()) {
                stopped = true
                break
            }

            try {
                val models = discover(provider)
                // Import/teardown may happen while the network request is in flight.
                // Never persist a response obtained for the previous provider snapshot.
                if (shouldStop()) {
                    stopped = true
                    break
                }
                require(models.isNotEmpty()) { "API zwróciło pustą listę" }
                save(provider, models)
                successes += provider
            } catch (cancelled: CancellationException) {
                stopped = true
                break
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                stopped = true
                break
            } catch (error: Exception) {
                // Isolate ordinary provider/network/storage failures, but do not swallow
                // fatal VM errors (OutOfMemoryError, LinkageError, etc.).
                failures += ProviderCatalogSyncFailure(
                    provider,
                    error.message?.take(500) ?: error.javaClass.simpleName
                )
                if (shouldStop()) {
                    stopped = true
                    break
                }
            }
        }
        return ProviderCatalogSyncReport(successes, failures, stopped)
    }
}
