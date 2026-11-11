package pl.aimasterpro.app.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.aimasterpro.app.core.ProviderConfig
import pl.aimasterpro.app.core.ProviderKind
import pl.aimasterpro.app.core.WorkMode
import pl.aimasterpro.app.network.ProviderClient

class TeamRunEngineTest {
    @Test
    fun unknownCloudProviderIsBlockedBeforeAnyNetworkRequest() {
        val engine = TeamRunEngine(ProviderClient())
        val groq = ProviderConfig(
            "groq", "Groq", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.groq.com/openai/v1", "some-current-model", "test-key"
        )
        val logs = mutableListOf<String>()
        val results = engine.runBatch(
            tasks = listOf(TeamTask("t1", "ai-engineer", "Test", "Zwróć krótki raport")),
            provider = groq,
            mode = WorkMode.PLANNING,
            onLog = { _, _, message, status -> logs.add("$status $message") }
        )
        assertEquals(1, results.size)
        assertEquals(TeamTaskResult.Status.FAILED, results.first().status)
        assertTrue(results.first().report.contains("BRAK DOSTĘPNEGO DARMOWEGO MODELU"))
        assertTrue(logs.any { it.contains("FREE FAILOVER GUARD") })
    }

    @Test
    fun managerSuppliedCustomAgentIsAcceptedByTeamEngineCatalog() {
        val custom = AgentSpec(
            id = "custom-test-auditor",
            name = "Testowy audytor",
            category = "Jakość, bezpieczeństwo i zgodność",
            responsibility = "Sprawdza poprawność konfiguracji.",
            keywords = listOf("audyt", "konfiguracja"),
            systemPromptOverride = "Nie ujawniaj sekretów."
        )
        val engine = TeamRunEngine(ProviderClient(), agentCatalog = listOf(custom))
        val provider = ProviderConfig(
            "custom-provider", "Własny", ProviderKind.OPENAI_COMPATIBLE,
            "https://example.invalid/v1", "unknown-model", "test-key"
        )
        val results = engine.runBatch(
            tasks = listOf(TeamTask("custom-task", custom.id, "Audyt", "Sprawdź konfigurację")),
            provider = provider,
            mode = WorkMode.PLANNING
        )
        assertEquals(1, results.size)
        assertEquals(custom.id, results.first().agentId)
        assertEquals(TeamTaskResult.Status.FAILED, results.first().status)
        assertTrue(results.first().report.contains("BRAK DOSTĘPNEGO DARMOWEGO MODELU"))
    }

    @Test
    fun paidFallbackIsNotAcceptedByTheFreeOnlyGuard() {
        val engine = TeamRunEngine(ProviderClient())
        val openRouter = ProviderConfig(
            "openrouter", "OpenRouter", ProviderKind.OPENAI_COMPATIBLE,
            "https://openrouter.ai/api/v1", "provider-model-without-free-marker", "test-key"
        )
        val groq = ProviderConfig(
            "groq", "Groq", ProviderKind.OPENAI_COMPATIBLE,
            "https://api.groq.com/openai/v1", "another-model", "test-key"
        )
        val logs = mutableListOf<String>()
        val results = engine.runBatch(
            tasks = listOf(TeamTask("t2", "ai-engineer", "Test", "Nie wykonuj zapytania")),
            provider = openRouter,
            fallbackProviders = listOf(groq),
            mode = WorkMode.PLANNING,
            onLog = { _, _, message, status -> logs.add("$status $message") }
        )
        assertEquals(TeamTaskResult.Status.FAILED, results.first().status)
        assertTrue(logs.any { it.contains("FREE FAILOVER GUARD") })
    }
}
