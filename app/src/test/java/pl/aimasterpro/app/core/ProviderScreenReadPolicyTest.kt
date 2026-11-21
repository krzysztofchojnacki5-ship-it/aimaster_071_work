package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderScreenReadPolicyTest {
    @Test
    fun successfulUsageReadPreservesNonNegativeCounter() {
        val result = ProviderScreenReadPolicy.readUsage { 12_345L }

        assertTrue(result.available)
        assertEquals(12_345L, result.totalTokens)
    }

    @Test
    fun failedUsageReadDoesNotExposeCounterOrThrowToScreen() {
        val result = ProviderScreenReadPolicy.readUsage {
            throw IllegalStateException("sensitive internal detail")
        }

        assertFalse(result.available)
        assertNull(result.totalTokens)
    }

    @Test
    fun negativeLegacyUsageIsNormalizedToZero() {
        val result = ProviderScreenReadPolicy.readUsage { -42L }

        assertTrue(result.available)
        assertEquals(0L, result.totalTokens)
    }

    @Test
    fun resolvesModelProfileFromSingleBaseProviderSnapshot() {
        val base = ProviderScreenProfileRecord("base", "Base", "GEMINI", "https://api.example/v1", "", "secret", true, "", false, false)
        val model = ProviderScreenProfileRecord("model", "Model", "OPENAI_COMPATIBLE", "", "model-x", "", false, "base", true, false)
        val resolved = ProviderScreenReadPolicy.resolveProfiles(listOf(base, model))
        assertEquals(2, resolved.size)
        assertEquals("model-x", resolved[1].model)
        assertEquals("GEMINI", resolved[1].kind)
        assertEquals("https://api.example/v1", resolved[1].baseUrl)
        assertEquals("secret", resolved[1].apiKey)
        assertTrue(resolved[1].enabled)
        assertFalse(resolved[1].allowInsecureLanOllama)
    }

    @Test
    fun excludesProfilesWithMissingOrUnreadableBaseProvider() {
        val orphan = ProviderScreenProfileRecord("orphan", "Orphan", "OPENAI_COMPATIBLE", "", "model", "", true, "missing", false, false)
        val unavailableBase = ProviderScreenProfileRecord("base", "Base", "GEMINI", "https://api.example/v1", "", "", true, "", false, true)
        val child = orphan.copy(id = "child", parentProviderId = "base")
        assertTrue(ProviderScreenReadPolicy.resolveProfiles(listOf(orphan, unavailableBase, child)).isEmpty())
    }

    @Test
    fun excludesAmbiguousDuplicateProviderIds() {
        val first = ProviderScreenProfileRecord("same", "First", "GEMINI", "https://one.example/v1", "", "", true, "", false, false)
        val second = first.copy(name = "Second", baseUrl = "https://two.example/v1")
        assertTrue(ProviderScreenReadPolicy.resolveProfiles(listOf(first, second)).isEmpty())
    }

    @Test
    fun excludesProfileWhenItsOwnSecretIsUnavailable() {
        val profile = ProviderScreenProfileRecord("broken", "Broken", "OPENAI_COMPATIBLE", "https://api.example/v1", "model", "", true, "", false, true)
        assertTrue(ProviderScreenReadPolicy.resolveProfiles(listOf(profile)).isEmpty())
    }

}
