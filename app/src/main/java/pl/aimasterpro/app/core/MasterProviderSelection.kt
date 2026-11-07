package pl.aimasterpro.app.core

/**
 * Resolves the canonical MASTER provider selection.
 *
 * The role assignment is authoritative once present. The legacy selected_provider value
 * is retained only as a migration/startup fallback for older installations.
 */
object MasterProviderSelection {
    fun resolve(assignments: Map<String, String>, legacySelectedProviderId: String?): String? {
        val assigned = assignments["MASTER"]?.takeIf { it.isNotBlank() }
        if (assigned != null) return assigned
        return legacySelectedProviderId?.takeIf { it.isNotBlank() }
    }
}
