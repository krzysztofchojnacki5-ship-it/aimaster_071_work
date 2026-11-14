package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderKeyDetectorTest {
    @Test fun detectsDistinctProviderKeyPrefixesWithoutNetworkAccess() {
        assertEquals(
            ProviderKeyDetection(ProviderKind.ANTHROPIC, "Anthropic", "https://api.anthropic.com/v1"),
            ProviderKeyDetector.detect("Bearer sk-ant-example")
        )
        assertEquals(
            ProviderKeyDetection(ProviderKind.GEMINI, "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
            ProviderKeyDetector.detect("AIza-example")
        )
        assertEquals(
            ProviderKeyDetection(ProviderKind.OPENAI_COMPATIBLE, "Groq", "https://api.groq.com/openai/v1"),
            ProviderKeyDetector.detect("gsk_example")
        )
        assertEquals(
            ProviderKeyDetection(ProviderKind.OPENAI_COMPATIBLE, "OpenRouter", "https://openrouter.ai/api/v1"),
            ProviderKeyDetector.detect("sk-or-v1-example")
        )
        assertEquals(
            ProviderKeyDetection(ProviderKind.OPENAI_COMPATIBLE, "xAI", "https://api.x.ai/v1"),
            ProviderKeyDetector.detect("xai-example")
        )
        assertEquals(
            ProviderKeyDetection(ProviderKind.OPENAI_COMPATIBLE, "OpenAI", "https://api.openai.com/v1"),
            ProviderKeyDetector.detect("sk-proj-example")
        )
    }

    @Test fun ambiguousGenericSkPrefixNeverGuessesProvider() {
        assertNull(ProviderKeyDetector.detect("sk-example"))
        assertTrue(ProviderKeyDetector.isAmbiguousGenericSecret("Bearer sk-example"))
        assertFalse(ProviderKeyDetector.isAmbiguousGenericSecret("sk-ant-example"))
        assertFalse(ProviderKeyDetector.isAmbiguousGenericSecret("sk-or-v1-example"))
    }

    @Test fun emptyAndOversizedKeysAreNotDetected() {
        assertNull(ProviderKeyDetector.detect(""))
        assertNull(ProviderKeyDetector.detect("   "))
        assertNull(ProviderKeyDetector.detect("x".repeat(8_193)))
    }
}
