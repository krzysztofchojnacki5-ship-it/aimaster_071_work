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
        assertTrue(FreeModelsCatalog.isFreeModel("groq", "openai/gpt-oss-20b"))
        assertTrue(FreeModelsCatalog.isFreeModel("groq", "openai/gpt-oss-120b"))
        assertTrue(FreeModelsCatalog.isFreeModel("groq", "qwen/qwen3.8-27b"))
        assertFalse(FreeModelsCatalog.isFreeModel("groq", "llama-3.3-70b-versatile"))
        assertEquals(FreeStatus.FREE_TIER, FreeModelsCatalog.classifyDiscoveredModel("groq", "openai/gpt-oss-20b").freeStatus)
        assertEquals(FreeStatus.UNKNOWN, FreeModelsCatalog.classifyDiscoveredModel("groq", "llama-3.3-70b-versatile").freeStatus)
    }

    @Test
    fun onlyExplicitOpenRouterFreeIdsAreAllowed() {
        assertTrue(FreeModelsCatalog.isFreeModel("openrouter", "openrouter/free"))
        assertTrue(FreeModelsCatalog.isFreeModel("openrouter", "qwen/model:free"))
        assertFalse(FreeModelsCatalog.isFreeModel("openrouter", "qwen/model"))
    }

    @Test
    fun customLanOllamaProfilesArePricedAsLocalButPublicHostsAreNotAssumedFree() {
        assertTrue(FreeModelsCatalog.isFreeModel("custom", "llama3", "http://192.168.1.50:11434/v1"))
        assertTrue(FreeModelsCatalog.isFreeModel("custom", "llama3", "http://172.20.0.5:11434/v1"))
        assertFalse(FreeModelsCatalog.isFreeModel("custom", "llama3", "https://public.example:11434/v1"))
        assertFalse(FreeModelsCatalog.isFreeModel("custom", "llama3", "http://192.168.1.50:8080/v1"))
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
        assertEquals(FreeStatus.UNKNOWN, FreeModelsCatalog.classifyDiscoveredModel("groq", "some-model").freeStatus)
        assertEquals(FreeStatus.FREE_TIER, FreeModelsCatalog.classifyDiscoveredModel("openrouter", "some/model:free").freeStatus)
    }
}
