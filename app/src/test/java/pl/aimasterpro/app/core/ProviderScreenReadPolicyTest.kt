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
}
