package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests. NOT RUN in this environment. */
class ModelGroupingAndCopyableTermsTest {
    private fun spec(providerId: String, providerName: String, modelId: String) = FreeModelSpec(
        providerId = providerId,
        providerName = providerName,
        modelId = modelId,
        modelName = modelId,
        freeStatus = FreeStatus.values().first()
    )

    @Test fun groupsModelsByProviderSortedByNameAndModelId() {
        val groups = ModelGrouping.groupByProvider(listOf(
            spec("openrouter", "OpenRouter", "b-model"),
            spec("gemini", "Gemini", "z-model"),
            spec("openrouter", "OpenRouter", "a-model")
        ))
        assertEquals(listOf("Gemini", "OpenRouter"), groups.map { it.providerName })
        assertEquals(listOf("a-model", "b-model"), groups[1].models.map { it.modelId })
    }

    @Test fun emptyInputGivesNoGroups() {
        assertTrue(ModelGrouping.groupByProvider(emptyList()).isEmpty())
    }

    @Test fun extractsInlineCodeAndLinksInOrder() {
        val text = "Użyj `gemini-2.0-flash` i sprawdź https://example.com/docs."
        assertEquals(listOf("gemini-2.0-flash", "https://example.com/docs"), CopyableTerms.extract(text))
    }

    @Test fun trimsTrailingPunctuationFromLinks() {
        assertEquals(listOf("https://example.com/a"), CopyableTerms.extract("Link: https://example.com/a)."))
    }

    @Test fun deduplicatesRepeatedTerms() {
        assertEquals(listOf("abc"), CopyableTerms.extract("`abc` i jeszcze `abc`"))
    }

    @Test fun ignoresMultilineCodeSpans() {
        assertEquals(emptyList<String>(), CopyableTerms.extract("`a\nb`"))
    }
}
