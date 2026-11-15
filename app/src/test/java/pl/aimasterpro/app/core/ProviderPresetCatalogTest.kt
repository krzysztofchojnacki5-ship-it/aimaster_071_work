package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test

class ProviderPresetCatalogTest {
    @Test
    fun includesMajorProvidersWithOfficialKeyCreationLinks() {
        val required = setOf("gemini", "groq", "openrouter", "anthropic", "openai", "mistral", "deepseek")
        assertTrue(ProviderPresetCatalog.all.map { it.id }.containsAll(required))
        ProviderPresetCatalog.all.forEach { preset ->
            assertTrue(preset.apiKeyUrl.startsWith("https://"))
            assertTrue(preset.baseUrl.startsWith("https://"))
            assertTrue(preset.name.isNotBlank())
            assertTrue(preset.modelLabel.isNotBlank())
        }
    }

    @Test
    fun presetsNeverContainUserCredentials() {
        ProviderPresetCatalog.all.forEach { preset ->
            assertTrue(preset.toProviderConfig().apiKey.isEmpty())
        }
    }

    @Test
    fun freeSuggestionsAreExplicitAndOpenAiDoesNotPretendChatGptPlanIsApiCredit() {
        assertEquals("gemini-3.8-flash", ProviderPresetCatalog.find("gemini")?.suggestedModelId)
        assertEquals("openai/gpt-oss-120b", ProviderPresetCatalog.find("groq")?.suggestedModelId)
        assertEquals("openrouter/free", ProviderPresetCatalog.find("openrouter")?.suggestedModelId)
        assertEquals("", ProviderPresetCatalog.find("openai")?.suggestedModelId)
        assertTrue(ProviderPresetCatalog.find("openai")!!.accessNote.contains("oddzielne"))
    }

    @Test
    fun unknownProviderLookupDoesNotInventPreset() {
        assertNull(ProviderPresetCatalog.find("not-a-provider"))
    }
}
