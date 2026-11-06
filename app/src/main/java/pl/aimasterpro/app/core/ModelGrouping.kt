package pl.aimasterpro.app.core

/**
 * Groups discovered models by provider for the Modele screen (pure logic, no UI).
 * Providers are sorted by display name, models by model ID, so the order is stable across refreshes.
 */
object ModelGrouping {
    data class Group(val providerId: String, val providerName: String, val models: List<FreeModelSpec>)

    fun groupByProvider(models: List<FreeModelSpec>): List<Group> =
        models.groupBy { it.providerId }
            .map { (providerId, list) ->
                Group(
                    providerId = providerId,
                    providerName = list.first().providerName,
                    models = list.sortedBy { it.modelId }
                )
            }
            .sortedBy { it.providerName.lowercase() }
}
