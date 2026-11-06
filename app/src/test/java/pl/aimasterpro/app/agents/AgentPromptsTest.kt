package pl.aimasterpro.app.agents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.aimasterpro.app.core.AgentRole

class AgentPromptsTest {
    @Test fun promptNeverClaimsUnconditionalFullRepositoryAccess() {
        val prompt = AgentPrompts.systemPrompt(AgentRole.DEVELOPER)
        assertTrue(prompt.contains("wyłącznie z plików, fragmentów, narzędzi i kontekstu rzeczywiście udostępnionych"))
        assertTrue(prompt.contains("Nie zakładaj pełnego wglądu w repozytorium"))
        assertFalse(prompt.contains("Masz pełny, rzeczywisty wgląd w całe repozytorium"))
    }

    @Test fun masterReceivesEngineeringAndOrchestrationProtocols() {
        val prompt = AgentPrompts.systemPrompt(AgentRole.MASTER)
        assertTrue(prompt.contains("PROTOKÓŁ PRACY INŻYNIERSKIEJ"))
        assertTrue(prompt.contains("ZASADY KOORDYNATORA MASTER"))
        assertTrue(prompt.contains("nie licz liczby zgodnych odpowiedzi jako dowodu prawdy"))
        assertTrue(prompt.contains("testy NOT RUN"))
        assertTrue(prompt.contains("Status COMPLETED agenta oznacza wyłącznie dostarczenie raportu"))
        assertTrue(prompt.contains("CLAIM_ONLY nie jest dowodem"))
        assertTrue(prompt.contains("Nie wybieraj najnowszego wpisu"))
        assertTrue(prompt.contains("hostowy resolver dla jawnego taskId/chatId/snapshotId"))
    }

    @Test fun contextIsIncludedOnlyWhenExplicitlyProvided() {
        val without = AgentPrompts.systemPrompt(AgentRole.REVIEWER)
        val with = AgentPrompts.systemPrompt(AgentRole.REVIEWER, "Repo snapshot: module A")
        assertFalse(without.contains("Repo snapshot: module A"))
        assertTrue(with.contains("KONTEKST PROJEKTU UDOSTĘPNIONY W TEJ SESJI"))
        assertTrue(with.contains("Repo snapshot: module A"))
    }

    @Test fun writerDoesNotReceiveUnneededEngineeringProtocol() {
        val prompt = AgentPrompts.systemPrompt(AgentRole.WRITER)
        assertFalse(prompt.contains("PROTOKÓŁ PRACY INŻYNIERSKIEJ"))
        assertTrue(prompt.contains("Dostęp do projektu:"))
    }
}
