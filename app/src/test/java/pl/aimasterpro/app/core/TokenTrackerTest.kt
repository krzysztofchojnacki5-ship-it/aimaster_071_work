package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TokenTrackerTest {
    private lateinit var context: Context

    @Before fun setUp() { context = RuntimeEnvironment.getApplication() }

    @Test fun malformedPersistedUsageDoesNotCrashProviderScreenRead() {
        val id = "provider-corrupt-json-test"
        context.getSharedPreferences("token_tracker_state", Context.MODE_PRIVATE)
            .edit().putString(id, "{broken-json").commit()
        val usage = TokenTracker.getUsage(context, id)
        assertEquals(id, usage.providerId)
        assertEquals(0L, usage.totalTokens)
        assertEquals("OK", usage.status)
    }

    @Test fun malformedPersistedUsageCanBeRecoveredByNextUsageWrite() {
        val id = "provider-corrupt-write-test"
        val prefs = context.getSharedPreferences("token_tracker_state", Context.MODE_PRIVATE)
        prefs.edit().putString(id, "{broken-json").commit()
        TokenTracker.recordUsage(context, id, "prompt", "response")
        val stored = org.json.JSONObject(prefs.getString(id, null)!!)
        assertEquals(1L, stored.getLong("input"))
        assertEquals(2L, stored.getLong("output"))
    }

    @Test fun negativeCountersAndUnknownStatusAreNormalized() {
        val id = "provider-invalid-values-test"
        context.getSharedPreferences("token_tracker_state", Context.MODE_PRIVATE)
            .edit().putString(id, org.json.JSONObject()
                .put("input", -10).put("output", 15).put("status", "UNKNOWN").toString()).commit()
        val usage = TokenTracker.getUsage(context, id)
        assertEquals(15L, usage.totalTokens)
        assertEquals("OK", usage.status)
    }

    @Test fun providerIdsAreValidatedBeforeReadingOrWriting() {
        assertFalse(runCatching { TokenTracker.getUsage(context, " ".repeat(161)) }.isSuccess)
        assertFalse(runCatching { TokenTracker.recordUsage(context, "bad\\nid", "p", "r") }.isSuccess)
    }

    @Test fun persistedFractionalAndOverflowCountersAreRejected() {
        val prefs = context.getSharedPreferences("token_tracker_state", Context.MODE_PRIVATE)
        prefs.edit().putString("provider-fractional-counter-test", """{"input":1.5,"output":2,"status":"OK","msg":"ok","ts":0}""").commit()
        assertEquals(0L, TokenTracker.getUsage(context, "provider-fractional-counter-test").totalTokens)
        prefs.edit().putString("provider-overflow-counter-test", """{"input":9223372036854775808,"output":2,"status":"OK","msg":"ok","ts":0}""").commit()
        assertEquals(0L, TokenTracker.getUsage(context, "provider-overflow-counter-test").totalTokens)
    }

    @Test fun persistedStatusMessageIsSanitizedOnReadAndWrite() {
        val id = "provider-secret-message-test"
        TokenTracker.recordUsage(context, id, "p", "r", statusMsg = "failed api_key=sk_live_1234567890123456")
        val raw = context.getSharedPreferences("token_tracker_state", Context.MODE_PRIVATE).getString(id, null)!!
        assertFalse(raw.contains("sk_live_1234567890123456"))
        assertTrue(raw.contains("[REDACTED]"))
    }

    @Test fun futureRateLimitTimestampIsRejectedInsteadOfExtendingCountdown() {
        val id = "provider-future-timestamp-test"
        val future = System.currentTimeMillis() + 600_000L
        val json = org.json.JSONObject().put("input", 1).put("output", 1)
            .put("status", "RATE_LIMITED_429").put("msg", "limited").put("ts", future)
        context.getSharedPreferences("token_tracker_state", Context.MODE_PRIVATE).edit().putString(id, json.toString()).commit()
        assertEquals(0L, TokenTracker.getUsage(context, id).totalTokens)
    }

    @Test fun durableWriteAndResetPersistBeforeUpdatingCachedState() {
        val id = "provider-durable-write-test"
        TokenTracker.recordUsage(context, id, "1234", "5678", statusMsg = "api_key=sk_live_1234567890123456")
        assertEquals(2L, TokenTracker.getUsage(context, id).totalTokens)
        TokenTracker.resetUsage(context, id)
        assertEquals(0L, TokenTracker.getUsage(context, id).totalTokens)
    }

}
