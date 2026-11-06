package pl.aimasterpro.app.agents

import java.util.Locale

/**
 * Token-aware, deterministic pre-router for the Android MASTER.
 * It performs no network/LLM call: casual chat always stays with MASTER.
 */
object AgentRoutingPolicy {
    enum class Mode { MASTER_ONLY, ONE_SPECIALIST, TEAM }

    data class Decision(
        val mode: Mode,
        val agents: List<AgentSpec>,
        val reason: String
    ) {
        val specialistCalls: Int get() = agents.size
    }

    private val actionSignals = listOf(
        "napraw", "sprawdź", "sprawdz", "przeanaliz", "zaprojekt", "zbuduj",
        "stwórz", "stworz", "utwórz", "utworz", "zaimplement", "popraw",
        "zmień", "zmien", "dodaj", "usuń", "usun", "przetest", "zoptymaliz",
        "porównaj", "porownaj", "audyt", "diagnoz", "wdroż", "wdroz",
        "skonfigur", "rozwiąż", "rozwiaz", "wyszukaj", "znajdź", "znajdz",
        "przygotuj", "wyjaśnij w kodzie", "wyjasnij w kodzie"
    )

    private val incidentSignals = listOf(
        "nie działa", "nie dziala", "crash", "wywala", "błąd kompilacji",
        "blad kompilacji", "exception", "stacktrace", "logcat", "gradle failed",
        "build failed", "http 401", "http 403", "http 429"
    )

    private val explicitTeamSignals = listOf(
        "uruchom cały zespół", "uruchom caly zespol", "wszyscy agenci",
        "cały zespół", "caly zespol", "zróbcie wspólnie", "zrobcie wspolnie",
        "niezależne opinie", "niezalezne opinie", "konsultacja zespołowa",
        "konsultacja zespolowa", "praca zespołowa", "praca zespolowa"
    )

    private val complexSignals = listOf(
        "od zera", "kompleksowo", "pełny projekt", "pelny projekt",
        "end-to-end", "zaprojektuj i zaimplementuj", "zbuduj i przetestuj",
        "architektura i implementacja", "audyt całego", "audyt calego"
    )

    fun route(prompt: String, qualityScore: (agentId: String, category: String) -> Double? = { _, _ -> null }): Decision {
        val text = prompt.lowercase(Locale.ROOT).trim()
        if (text.isBlank()) return masterOnly("Pusta wiadomość")

        val explicitTeam = explicitTeamSignals.any(text::contains)
        val incident = incidentSignals.any(text::contains)
        // An attachment alone is not a reason to wake specialists; MASTER can inspect it.
        val actionable = actionSignals.any(text::contains) || incident
        if (!actionable && !explicitTeam) {
            return masterOnly("Zwykła rozmowa/pytanie — bez wywołań specjalistów")
        }

        val rankedBase = AgentCatalog.scoreForPrompt(text)
            .filter { it.agent.id != "lead-coordinator" && it.agent.id != "planning-lead" }
            // Learned quality is only a tie-breaker: it can never outrank a better keyword match.
            .sortedWith(compareByDescending<AgentCatalog.AgentMatch> { it.keywordScore }
                .thenByDescending { qualityScore(it.agent.id, it.agent.category) ?: 0.5 }
                .thenBy { it.agent.id })
            .map { it.agent }
        val diagnosticsLead = AgentCatalog.find("diagnostics-lead")
        val ranked = if (incident && diagnosticsLead != null && rankedBase.contains(diagnosticsLead)) {
            listOf(diagnosticsLead) + rankedBase.filterNot { it.id == diagnosticsLead.id }
        } else rankedBase
        if (ranked.isEmpty() && !explicitTeam) {
            return masterOnly("Brak dopasowanego specjalisty — MASTER odpowiada sam")
        }

        if (explicitTeam) {
            val team = diverse(ranked, 4).toMutableList()
            // An explicit request for a team should mean more than one specialist,
            // even when the prompt contains only one narrow technical keyword.
            listOf("critical-reviewer", "source-verifier", "requirements-analyst").forEach { id ->
                val helper = AgentCatalog.find(id)
                if (team.size < 3 && helper != null && team.none { it.id == id }) team += helper
            }
            return Decision(Mode.TEAM, team.take(4), "Użytkownik jawnie zażądał pracy zespołowej")
        }

        val isComplex = complexSignals.any(text::contains)
        val distinctDomains = ranked.map { it.category }.distinct().size
        if (isComplex && distinctDomains >= 2) {
            return Decision(Mode.TEAM, diverse(ranked, 3), "Zadanie wieloetapowe i wielodziedzinowe")
        }

        return Decision(Mode.ONE_SPECIALIST, ranked.take(1), "Jedna specjalistyczna kompetencja wystarcza")
    }

    private fun diverse(ranked: List<AgentSpec>, limit: Int): List<AgentSpec> {
        val chosen = mutableListOf<AgentSpec>()
        val categories = mutableSetOf<String>()
        ranked.forEach { spec ->
            if (chosen.size < limit && categories.add(spec.category)) chosen += spec
        }
        if (chosen.size < limit) {
            ranked.forEach { spec -> if (chosen.size < limit && spec !in chosen) chosen += spec }
        }
        return chosen
    }

    private fun masterOnly(reason: String) = Decision(Mode.MASTER_ONLY, emptyList(), reason)
}
