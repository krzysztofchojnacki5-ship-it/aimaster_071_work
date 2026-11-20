package pl.aimasterpro.app.core

/**
 * Read-only aggregation of durable-store health for the Diagnostics screen.
 * A failed probe is never represented as an empty or healthy store.
 */
object DiagnosticsStorageHealth {
    enum class OverallStatus { HEALTHY, DEGRADED, UNAVAILABLE }

    data class Source(
        val id: String,
        val label: String,
        val available: Boolean,
        val detail: String
    )

    data class Snapshot(
        val sources: List<Source>,
        val overallStatus: OverallStatus
    ) {
        val availableCount: Int get() = sources.count { it.available }
        val unavailableCount: Int get() = sources.size - availableCount

        val summary: String
            get() = when (overallStatus) {
                OverallStatus.HEALTHY -> "WSZYSTKIE MAGAZYNY DOSTĘPNE ($availableCount/${sources.size}). Kontrola nie modyfikuje danych."
                OverallStatus.DEGRADED -> "CZĘŚĆ MAGAZYNÓW NIEDOSTĘPNA ($availableCount/${sources.size}). Odzyskiwanie może być niepełne."
                OverallStatus.UNAVAILABLE -> "BRAK POTWIERDZONEGO DOSTĘPU DO MAGAZYNÓW (0/${sources.size}). Nie wykonuj operacji odzyskiwania."
            }

        val details: String
            get() = sources.joinToString("\n") { source ->
                "${if (source.available) "✓" else "⚠"} ${source.label}: ${if (source.available) "ODCZYT OK" else "NIEDOSTĘPNY"} — ${source.detail}"
            }
    }

    fun build(sources: List<Source>): Snapshot {
        require(sources.isNotEmpty()) { "At least one durable store must be checked." }
        require(sources.size <= 20) { "Too many diagnostic store sources." }
        require(sources.map { it.id }.distinct().size == sources.size) {
            "Diagnostic store source IDs must be unique."
        }
        require(sources.all {
            it.id.isNotBlank() && it.id.length <= 80 &&
                it.label.isNotBlank() && it.label.length <= 120 &&
                it.detail.length <= 180
        }) { "Diagnostic store metadata is invalid or oversized." }

        val available = sources.count { it.available }
        val overall = when (available) {
            sources.size -> OverallStatus.HEALTHY
            0 -> OverallStatus.UNAVAILABLE
            else -> OverallStatus.DEGRADED
        }
        return Snapshot(sources.toList(), overall)
    }
}
