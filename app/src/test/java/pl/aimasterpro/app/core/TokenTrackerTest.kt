package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.assertEquals
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
}
