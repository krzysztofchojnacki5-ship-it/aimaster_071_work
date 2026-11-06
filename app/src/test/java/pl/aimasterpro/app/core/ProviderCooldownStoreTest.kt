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
    @Before fun reset() { context.getSharedPreferences("provider_cooldowns_v1", Context.MODE_PRIVATE).edit().clear().commit() }

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
}
