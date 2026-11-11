package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeModelsCatalogTest {
    @Test
    fun unknownCloudModelsAreNeverClassifiedAsFree() {
        assertFalse(FreeModelsCatalog.isFreeModel("groq", "new-model"))
        assertFalse(FreeModelsCatalog.isFreeModel("gemini", "new-model"))
        assertFalse(FreeModelsCatalog.isFreeModel("mistral", "new-model"))
        assertFalse(FreeModelsCatalog.isFreeModel("openai", "gpt-4o"))
    }

    @Test
    fun onlyOfficialGroqFreePlanModelsAreAllowed() {
        assertTrue(FreeModelsCatalog.isFreeModel("groq", "openai/gpt-oss-20b", "https://api.groq.com/openai/v1"))
        assertTrue(FreeModelsCatalog.isFreeModel("groq", "openai/gpt-oss-120b", "https://api.groq.com/openai/v1"))
        assertTrue(FreeModelsCatalog.isFreeModel("groq", "qwen/qwen3.8-27b", "https://api.groq.com/openai/v1"))
        assertFalse(FreeModelsCatalog.isFreeModel("groq", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1"))
        assertEquals(FreeStatus.FREE_TIER, FreeModelsCatalog.classifyDiscoveredModel("groq", "openai/gpt-oss-20b", "Groq", "groq", "https://api.groq.com/openai/v1").freeStatus)
        assertEquals(FreeStatus.UNKNOWN, FreeModelsCatalog.classifyDiscoveredModel("groq", "llama-3.3-70b-versatile", "Groq", "groq", "https://api.groq.com/openai/v1").freeStatus)
    }

    @Test
    fun onlyExplicitOpenRouterFreeIdsAreAllowed() {
        assertTrue(FreeModelsCatalog.isFreeModel("openrouter", "openrouter/free", "https://openrouter.ai/api/v1"))
        assertTrue(FreeModelsCatalog.isFreeModel("openrouter", "qwen/model:free", "https://openrouter.ai/api/v1"))
        assertFalse(FreeModelsCatalog.isFreeModel("openrouter", "qwen/model", "https://openrouter.ai/api/v1"))
    }

    @Test
    fun cloudProviderNamesCannotSpoofFreeTierWithoutOfficialEndpointHost() {
        assertFalse(FreeModelsCatalog.isFreeModel("groq", "openai/gpt-oss-20b"))
        assertFalse(FreeModelsCatalog.isFreeModel("openrouter", "qwen/model:free"))
        assertEquals(
            FreeStatus.UNKNOWN,
            FreeModelsCatalog.classifyDiscoveredModel("groq", "openai/gpt-oss-20b", "Groq", "groq", "https://groq.example/v1").freeStatus
        )
        assertEquals(
            FreeStatus.UNKNOWN,
            FreeModelsCatalog.classifyDiscoveredModel("openrouter", "qwen/model:free", "OpenRouter", "openrouter", "https://openrouter.example/api/v1").freeStatus
        )
    }

    @Test
    fun customLanOllamaProfilesArePricedAsLocalButPublicHostsAreNotAssumedFree() {
        assertTrue(FreeModelsCatalog.isFreeModel("custom", "llama3", "http://192.168.1.50:11434/v1"))
        assertTrue(FreeModelsCatalog.isFreeModel("custom", "llama3", "http://172.20.0.5:11434/v1"))
        assertFalse(FreeModelsCatalog.isFreeModel("custom", "llama3", "https://public.example:11434/v1"))
        assertFalse(FreeModelsCatalog.isFreeModel("custom", "llama3", "http://192.168.1.50:8080/v1"))
    }

    @Test
    fun localOllamaEndpointMustHaveSafeSchemeAuthorityAndApiRoot() {
        assertTrue(FreeModelsCatalog.isLocalOllamaEndpoint("http://192.168.1.50:11434/v1"))
        assertTrue(FreeModelsCatalog.isLocalOllamaEndpoint("https://localhost:11434"))
        assertFalse(FreeModelsCatalog.isLocalOllamaEndpoint("ftp://localhost:11434/v1"))
        assertFalse(FreeModelsCatalog.isLocalOllamaEndpoint("http://user@localhost:11434/v1"))
        assertFalse(FreeModelsCatalog.isLocalOllamaEndpoint("http://localhost:11434/v1?token=secret"))
        assertFalse(FreeModelsCatalog.isLocalOllamaEndpoint("http://localhost:11434/api/pull"))
        assertFalse(FreeModelsCatalog.isLocalOllamaEndpoint("http://public.example:11434/v1"))
        assertFalse(FreeModelsCatalog.isLocalOllamaEndpoint("http://127.attacker.example:11434/v1"))
        assertFalse(FreeModelsCatalog.isLocalOllamaEndpoint("http://10.attacker.example:11434/v1"))
    }

    @Test
    fun ollamaNameAloneNeverProvesLocalOrFreeEndpoint() {
        val publicEndpoint = "https://ollama.example:11434/v1"
        assertFalse(FreeModelsCatalog.isFreeModel("ollama", "llama3", publicEndpoint))
        assertEquals(
            FreeStatus.UNKNOWN,
            FreeModelsCatalog.classifyDiscoveredModel("ollama", "llama3", "Ollama", "ollama", publicEndpoint).freeStatus
        )
        assertEquals(
            FreeStatus.FREE,
            FreeModelsCatalog.classifyDiscoveredModel(
                "ollama", "llama3", "Ollama", "ollama", "http://127.0.0.1:11434/v1"
            ).freeStatus
        )
        assertTrue(FreeModelsCatalog.isFreeModel("ollama", "llama3", "http://127.0.0.1:11434/v1"))
    }

    @Test
    fun localOllamaModelsAreFreeButCloudUnknownsStayUnknown() {
        assertFalse(FreeModelsCatalog.isFreeModel("ollama", "llama3"))
        assertTrue(FreeModelsCatalog.isFreeModel("custom", "llama3", "http://10.0.2.2:11434/v1"))
        assertEquals(FreeStatus.UNKNOWN, FreeModelsCatalog.classifyDiscoveredModel("groq", "some-model", "Groq", "groq", "https://api.groq.com/openai/v1").freeStatus)
        assertEquals(FreeStatus.FREE_TIER, FreeModelsCatalog.classifyDiscoveredModel("openrouter", "some/model:free", "OpenRouter", "openrouter", "https://openrouter.ai/api/v1").freeStatus)
    }
}
