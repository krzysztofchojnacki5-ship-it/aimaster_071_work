package pl.aimasterpro.app.core

import java.util.Locale

/** Deterministic warning-only detector for directly comparable numeric claims across agent reports. */
object ReportConflictDetector {
    data class Report(val agentId: String, val text: String)
    data class Conflict(
        val claimKey: String,
        val values: List<String>,
        val sources: List<String>
    )

    private val numberRegex = Regex("(?<![\\p{L}])[-+]?\\d+(?:[.,]\\d+)?(?:\\s?(?:%|ms|s|min|h|kg|g|km|cm|mm|MB|GB|KB|token(?:y|ów)?))?", RegexOption.IGNORE_CASE)
    private val leadingMarker = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s*")
    private val punctuation = Regex("[^\\p{L}\\p{N}%]+")

    fun detect(reports: List<Report>, maxConflicts: Int = 8): List<Conflict> {
        require(maxConflicts >= 0)
        if (maxConflicts == 0 || reports.size < 2) return emptyList()
        data class Claim(val agent: String, val original: String, val values: String)
        val claims = reports.flatMap { report ->
            report.text.lineSequence().mapNotNull { raw ->
                val line = leadingMarker.replace(raw, "").trim()
                if (line.length !in 18..320) return@mapNotNull null
                val matches = numberRegex.findAll(line).map { it.value.trim().replace(',', '.') }.toList()
                if (matches.isEmpty()) return@mapNotNull null
                val key = punctuation.replace(numberRegex.replace(line.lowercase(Locale.ROOT), " <num> "), " ")
                    .replace(Regex("\\s+"), " ").trim()
                if (key.length < 12) return@mapNotNull null
                Claim(report.agentId, key, matches.joinToString(", "))
            }
        }
        val conflicts = claims.groupBy { it.original }.values.mapNotNull { group ->
            val byAgent = group.groupBy { it.agent }
            if (byAgent.size < 2) return@mapNotNull null
            val valuesByAgent = byAgent.mapValues { (_, rows) -> rows.flatMap { it.values.split(", ") }.distinct() }
            val distinctValues = valuesByAgent.values.flatten().distinct()
            if (distinctValues.size < 2) return@mapNotNull null
            Conflict(group.first().original, distinctValues.take(6), valuesByAgent.map { (agent, values) -> "$agent: ${values.joinToString(" / ")}" }.take(6))
        }
        return conflicts.distinctBy { it.claimKey }.take(maxConflicts)
    }

    fun toMasterInstruction(conflicts: List<Conflict>): String {
        if (conflicts.isEmpty()) return "Brak wykrytych bezpośrednich rozbieżności liczbowych. To nie jest dowód pełnej zgodności raportów."
        return buildString {
            appendLine("WYKRYTE ROZBIEŻNOŚCI LICZBOWE — wymagają jawnego rozstrzygnięcia:")
            conflicts.forEachIndexed { index, conflict ->
                appendLine("${index + 1}. Twierdzenie: ${conflict.claimKey}")
                conflict.sources.forEach { appendLine("   - $it") }
            }
            append("Nie wybieraj arbitralnie jednej wartości. Jeśli raporty nie zawierają dowodu rozstrzygającego, przedstaw rozbieżność i oznacz ją jako nierozstrzygniętą. Nie uruchamiaj dodatkowych modeli tylko z tego powodu.")
        }
    }
}
