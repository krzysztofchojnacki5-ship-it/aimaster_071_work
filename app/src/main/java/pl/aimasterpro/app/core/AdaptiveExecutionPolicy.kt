package pl.aimasterpro.app.core

import java.util.Locale

/** Deterministic local policy: no model call is made to estimate task difficulty. */
object AdaptiveExecutionPolicy {
    data class Plan(val maxAgents: Int, val outputTokensPerAgent: Long, val synthesisReserveTokens: Long, val difficulty: Int, val reason: String)

    fun plan(prompt: String, candidateAgents: Int, explicitTeam: Boolean = false, teamMode: Boolean = false): Plan {
        val p = prompt.lowercase(Locale.ROOT)
        val signals = listOf("od zera", "kompleksowo", "audyt", "architektura", "zaimplementuj", "zbuduj i przetestuj", "end-to-end", "wielowątk", "bezpieczeń", "bezpieczen", "migrac", "refaktor")
        val domains = listOf("android", "kotlin", "gradle", "api", "baza danych", "interfejs", "bezpieczeń", "bezpieczen", "testy", "architektura", "sieć", "siec").count { p.contains(it) }
        val difficulty = (signals.count { p.contains(it) } + (domains / 2) + if (p.length > 1200) 1 else 0).coerceIn(0, 5)
        val cap = when { explicitTeam -> 4; teamMode && difficulty >= 4 -> 3; teamMode -> 2; difficulty >= 4 -> 3; difficulty >= 2 -> 2; else -> 1 }
        val count = candidateAgents.coerceAtMost(cap).coerceAtLeast(if (candidateAgents > 0) 1 else 0)
        val output = when (difficulty) { 0, 1 -> 1400L; 2, 3 -> 2200L; else -> 3000L }
        val reserve = when (difficulty) { 0, 1 -> 1800L; 2, 3 -> 2800L; else -> 4200L }
        return Plan(count, output, reserve, difficulty, "trudność=$difficulty, limit agentów=$count, budżet raportu=$output")
    }
}
