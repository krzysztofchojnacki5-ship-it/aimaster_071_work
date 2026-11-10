package pl.aimasterpro.app.core

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

        for (provider in providers) {
            if (isCancelled()) {
                stopped = true
                break
            }
            val result = runCatching {
                val models = discover(provider)
                require(models.isNotEmpty()) { "API zwróciło pustą listę" }
                check(!isCancelled()) { "Synchronizacja została przerwana" }
                save(provider, models)
            }
            result.fold(
                onSuccess = { successes += provider },
                onFailure = { error ->
                    failures += ProviderCatalogSyncFailure(
                        provider,
                        error.message?.take(500) ?: error.javaClass.simpleName
                    )
                }
            )
        }
        return ProviderCatalogSyncReport(successes, failures, stopped)
    }
}
