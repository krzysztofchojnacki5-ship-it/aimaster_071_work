package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogCachePolicyTest {
    @Test fun sameProviderAndEndpointKeepsCachedCatalog() {
        assertTrue(ProviderCatalogCachePolicy.isEntryCurrent(
            "provider-a", "https://api.example.com/v1/",
            mapOf("provider-a" to "https://api.example.com/v1")
        ))
    }

    @Test fun reusedProviderIdWithDifferentEndpointRejectsOldCatalog() {
        assertFalse(ProviderCatalogCachePolicy.isEntryCurrent(
            "provider-a", "https://old.example.com/v1",
            mapOf("provider-a" to "https://new.example.com/v1")
        ))
    }

    @Test fun removedProviderRejectsOrphanedCatalog() {
        assertFalse(ProviderCatalogCachePolicy.isEntryCurrent(
            "removed-provider", "https://api.example.com/v1", emptyMap()
        ))
    }

    @Test fun legacyCatalogWithoutEndpointFailsClosed() {
        assertFalse(ProviderCatalogCachePolicy.isEntryCurrent(
            "provider-a", "", mapOf("provider-a" to "https://api.example.com/v1")
        ))
    }

    @Test fun normalizationIgnoresHostCaseAndTrailingSlashOnly() {
        assertTrue(ProviderCatalogCachePolicy.isEntryCurrent(
            "provider-a", "HTTPS://API.EXAMPLE.COM/v1///",
            mapOf("provider-a" to "https://api.example.com/v1")
        ))
    }

    @Test fun endpointPathRemainsSignificant() {
        assertFalse(ProviderCatalogCachePolicy.isEntryCurrent(
            "provider-a", "https://api.example.com/v1",
            mapOf("provider-a" to "https://api.example.com/v2")
        ))
    }

    @Test fun providerSnapshotIncludesOnlyUniqueBaseProviders() {
        val base = ProviderScreenProfileRecord(
            "provider-a", "Provider A", "OPENAI_COMPATIBLE", "https://api.example/v1", "", "key",
            true, "", false, false
        )
        val model = base.copy(id = "model-a", name = "Model A", parentProviderId = "provider-a")
        val duplicateA = base.copy(name = "Duplicate A", baseUrl = "https://other.example/v1")
        val blankEndpoint = base.copy(id = "provider-empty", baseUrl = "")
        assertEquals(
            mapOf("provider-a" to "https://api.example/v1"),
            ProviderCatalogCachePolicy.currentProviderBaseUrls(listOf(base, model, duplicateA, blankEndpoint))
        )
    }

    @Test fun providerSnapshotDoesNotDropEndpointWhenSecretIsUnavailable() {
        val unreadable = ProviderScreenProfileRecord(
            "provider-a", "Provider A", "OPENAI_COMPATIBLE", "https://api.example/v1", "", "",
            true, "", false, true
        )
        assertEquals(
            mapOf("provider-a" to "https://api.example/v1"),
            ProviderCatalogCachePolicy.currentProviderBaseUrls(listOf(unreadable))
        )
    }

}
