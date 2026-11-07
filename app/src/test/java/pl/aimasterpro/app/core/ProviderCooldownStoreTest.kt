package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderCooldownStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val config = ProviderConfig("groq", "Groq", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "openai/gpt-oss-20b")
    @Before fun reset() { SharedPreferencesWriteHealth.resetForTests("provider_cooldowns_v1"); context.getSharedPreferences("provider_cooldowns_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun cooldownPersistsAndUsesExponentialBackoff() {
        val now = 1_000_000L
        assertEquals(0L, ProviderCooldownStore.remainingMs(context, config, now))
        assertEquals(60_000L, ProviderCooldownStore.markRateLimited(context, config, now))
        assertEquals(60_000L, ProviderCooldownStore.remainingMs(context, config, now))
        assertEquals(120_000L, ProviderCooldownStore.markRateLimited(context, config, now))
        assertEquals(120_000L, ProviderCooldownStore.remainingMs(context, config, now))
        assertEquals(0L, ProviderCooldownStore.remainingMs(context, config, now + 121_000L))
    }

    @Test fun successResetsCooldownAndStreak() {
        ProviderCooldownStore.markRateLimited(context, config, 1000L)
        ProviderCooldownStore.markRateLimited(context, config, 2000L)
        ProviderCooldownStore.markSuccess(context, config, 3000L)
        assertEquals(0L, ProviderCooldownStore.remainingMs(context, config, 3000L))
        assertEquals(0, ProviderCooldownStore.states(context).single().consecutive429)
    }

    @Test fun malformedJournalFailsClosedAndIsNeverOverwritten() {
        val prefs = context.getSharedPreferences("provider_cooldowns_v1", Context.MODE_PRIVATE)
        val malformed = """[{"key":"api.groq.com|groq|openai/gpt-oss-20b","count":"2","until":2000000,"updated":1000000}]"""
        assertTrue(prefs.edit().putString("states", malformed).commit())

        assertEquals(900_000L, ProviderCooldownStore.remainingMs(context, config, 1_000_000L))
        assertEquals(900_000L, ProviderCooldownStore.markRateLimited(context, config, 1_000_000L))
        ProviderCooldownStore.markSuccess(context, config, 1_000_000L)

        assertEquals(malformed, prefs.getString("states", null))
        assertTrue(runCatching { ProviderCooldownStore.states(context) }.isFailure)
    }

    @Test fun malformedRowInvalidatesWholeJournalInsteadOfDroppingOnlyThatRow() {
        val prefs = context.getSharedPreferences("provider_cooldowns_v1", Context.MODE_PRIVATE)
        val mixed = """[{"key":"valid|p|m","count":1,"until":2000000,"updated":1000000},{"key":"bad|p|m","count":1.5,"until":2000000,"updated":1000000}]"""
        assertTrue(prefs.edit().putString("states", mixed).commit())

        assertEquals(900_000L, ProviderCooldownStore.remainingMs(context, config, 1_000_000L))
        assertEquals(mixed, prefs.getString("states", null))
    }

    @Test fun duplicateCooldownIdentityIsRejected() {
        val prefs = context.getSharedPreferences("provider_cooldowns_v1", Context.MODE_PRIVATE)
        val duplicate = """[{"key":"same","count":1,"until":2000000,"updated":1000000},{"key":"same","count":2,"until":3000000,"updated":1000000}]"""
        assertTrue(prefs.edit().putString("states", duplicate).commit())
        assertEquals(900_000L, ProviderCooldownStore.remainingMs(context, config, 1_000_000L))
    }
    @Test fun ambiguousCommitTreatsEveryProviderAsCoolingDownAndBlocksWrites() {
        val prefs = context.getSharedPreferences("provider_cooldowns_v1", Context.MODE_PRIVATE)
        val original = "[]"
        assertTrue(prefs.edit().putString("states", original).commit())
        val failingEditor = object : android.content.SharedPreferences.Editor by prefs.edit().putString("states", "[ambiguous]") {
            override fun commit(): Boolean = false
        }
        assertFalse(SharedPreferencesWriteHealth.commit("provider_cooldowns_v1", failingEditor))

        assertEquals(900_000L, ProviderCooldownStore.remainingMs(context, config, 1_000_000L))
        assertEquals(900_000L, ProviderCooldownStore.markRateLimited(context, config, 1_000_000L))
        ProviderCooldownStore.markSuccess(context, config, 1_000_000L)
        assertTrue(runCatching { ProviderCooldownStore.states(context) }.isFailure)
        assertEquals(original, prefs.getString("states", null))
    }
}
