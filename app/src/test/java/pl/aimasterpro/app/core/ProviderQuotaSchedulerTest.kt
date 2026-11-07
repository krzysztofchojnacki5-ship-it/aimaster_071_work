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

    @Test fun concurrentDoubleCloseCannotReleaseAnotherWorkersLease() {
        val key = ProviderQuotaScheduler.key("provider", "https://api.example.test/v1", "shared-model")
        val first = ProviderQuotaScheduler.acquire(key, isFreeTier = false, timeoutMs = 0)
        assertNotNull(first.lease)
        Thread.sleep(275L)
        val second = ProviderQuotaScheduler.acquire(key, isFreeTier = false, timeoutMs = 0)
        assertNotNull(second.lease)
        assertEquals(2, ProviderQuotaScheduler.activeCountForTests(key))

        val ready = java.util.concurrent.CountDownLatch(32)
        val start = java.util.concurrent.CountDownLatch(1)
        val closers = (1..32).map {
            Thread {
                ready.countDown()
                start.await()
                first.lease!!.close()
            }.apply { start() }
        }
        assertTrue("All concurrent close callers must reach the start gate", ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        closers.forEach { it.join(2_000L) }

        assertTrue("Concurrent close callers must finish", closers.none { it.isAlive })
        assertEquals("Closing one lease concurrently must release exactly one slot",
            1, ProviderQuotaScheduler.activeCountForTests(key))
        second.lease!!.close()
        assertEquals(0, ProviderQuotaScheduler.activeCountForTests(key))
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
