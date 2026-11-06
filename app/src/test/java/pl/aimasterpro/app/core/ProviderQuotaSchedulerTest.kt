package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderQuotaSchedulerTest {
    @Before fun reset() = ProviderQuotaScheduler.resetForTests()

    @Test fun freeModelAllowsOnlyOneConcurrentLeaseForSameProviderAndModel() {
        val key = ProviderQuotaScheduler.key("openrouter", "https://openrouter.ai/api/v1", "model:free")
        val first = ProviderQuotaScheduler.acquire(key, isFreeTier = true, timeoutMs = 0)
        assertNotNull(first.lease)
        val second = ProviderQuotaScheduler.acquire(key, isFreeTier = true, timeoutMs = 0)
        assertNull(second.lease)
        assertEquals("MODEL_BUSY_TIMEOUT", second.reason)
        first.lease?.close()
    }

    @Test fun observed429PlacesOnlyThatModelIntoCooldown() {
        val firstKey = ProviderQuotaScheduler.key("groq", "https://api.groq.com/openai/v1", "model-a")
        val secondKey = ProviderQuotaScheduler.key("groq", "https://api.groq.com/openai/v1", "model-b")
        val cooldown = ProviderQuotaScheduler.markRateLimited(firstKey)
        assertTrue(cooldown >= 60_000L)
        val blocked = ProviderQuotaScheduler.acquire(firstKey, isFreeTier = true, timeoutMs = 0)
        val allowed = ProviderQuotaScheduler.acquire(secondKey, isFreeTier = true, timeoutMs = 0)
        assertNull(blocked.lease)
        assertEquals("MODEL_COOLDOWN", blocked.reason)
        assertNotNull(allowed.lease)
        allowed.lease?.close()
    }
}
