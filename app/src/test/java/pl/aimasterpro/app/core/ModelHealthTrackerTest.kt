package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ModelHealthTrackerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val primary = ProviderConfig("p1", "Primary", ProviderKind.OPENAI_COMPATIBLE, "https://api.primary.test/v1", "model-a")
    private val backup = ProviderConfig("p2", "Backup", ProviderKind.OPENAI_COMPATIBLE, "https://api.backup.test/v1", "model-b")

    @Before fun reset() { context.getSharedPreferences("model_health_history_v1", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun unknownBackupDoesNotReplaceConfiguredPrimary() {
        ModelHealthTracker.record(context, primary, false, "HTTP_429", 0)
        ModelHealthTracker.record(context, primary, false, "HTTP_429", 0)
        assertEquals("p1", ModelHealthTracker.rank(context, listOf(primary, backup)).first().id)
    }

    @Test fun oneTransientFailureDoesNotDemotePrimary() {
        ModelHealthTracker.record(context, primary, false, "HTTP_429", 0)
        ModelHealthTracker.record(context, backup, true, "", 800)
        assertEquals("p1", ModelHealthTracker.rank(context, listOf(primary, backup)).first().id)
    }

    @Test fun repeatedPrimaryFailuresPromoteProvenHealthyBackup() {
        repeat(3) { ModelHealthTracker.record(context, primary, false, "HTTP_429", 0) }
        repeat(3) { ModelHealthTracker.record(context, backup, true, "", 800) }
        assertEquals("p2", ModelHealthTracker.rank(context, listOf(primary, backup)).first().id)
    }

    @Test fun snapshotIsScopedToExactHostAndModel() {
        ModelHealthTracker.record(context, primary, true, "", 500)
        assertNotNull(ModelHealthTracker.snapshot(context, primary))
        val other = primary.copy(model = "model-c")
        assertNull(ModelHealthTracker.snapshot(context, other))
    }

    @Test fun malformedHistoryFailsClosedAndIsNeverOverwritten() {
        val prefs = context.getSharedPreferences("model_health_history_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"ts":"bad","provider":"p1","host":"api.primary.test","model":"model-a","ok":true,"category":"","latency":1}]"""
        assertTrue(prefs.edit().putString("events", corrupt).commit())

        assertTrue(runCatching { ModelHealthTracker.load(context) }.isFailure)
        assertEquals("p1", ModelHealthTracker.rank(context, listOf(primary, backup)).first().id)
        assertTrue(runCatching { ModelHealthTracker.record(context, backup, true, "", 100) }.isFailure)
        assertEquals(corrupt, prefs.getString("events", null))
    }

    @Test fun malformedHistoryRowCannotBeSilentlyDropped() {
        val prefs = context.getSharedPreferences("model_health_history_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"ts":1,"provider":"p1","host":"api.primary.test","model":"model-a","ok":true,"category":"","latency":1},null]"""
        assertTrue(prefs.edit().putString("events", corrupt).commit())
        assertTrue(runCatching { ModelHealthTracker.load(context) }.isFailure)
        assertEquals(corrupt, prefs.getString("events", null))
    }

    @Test fun outOfRangeIntegerBeyondLongMaxIsRejected() {
        val prefs = context.getSharedPreferences("model_health_history_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"ts":9223372036854775808,"provider":"p1","host":"api.primary.test","model":"model-a","ok":true,"category":"","latency":1}]"""
        assertTrue(prefs.edit().putString("events", corrupt).commit())
        assertTrue(runCatching { ModelHealthTracker.load(context) }.isFailure)
        assertEquals(corrupt, prefs.getString("events", null))
    }

    @Test fun averageLatencyDoesNotOverflowLongAccumulation() {
        ModelHealthTracker.record(context, primary, true, "", Long.MAX_VALUE)
        ModelHealthTracker.record(context, primary, true, "", Long.MAX_VALUE - 2L)
        assertEquals(Long.MAX_VALUE - 1L, ModelHealthTracker.snapshot(context, primary)?.averageLatencyMs)
    }

    @Test fun negativeOrFractionalHistoryCountersAreRejected() {
        val prefs = context.getSharedPreferences("model_health_history_v1", Context.MODE_PRIVATE)
        val negative = """[{"ts":-1,"provider":"p1","host":"api.primary.test","model":"model-a","ok":true,"category":"","latency":1}]"""
        assertTrue(prefs.edit().putString("events", negative).commit())
        assertTrue(runCatching { ModelHealthTracker.load(context) }.isFailure)
        val fractional = """[{"ts":1.5,"provider":"p1","host":"api.primary.test","model":"model-a","ok":true,"category":"","latency":1}]"""
        assertTrue(prefs.edit().putString("events", fractional).commit())
        assertTrue(runCatching { ModelHealthTracker.load(context) }.isFailure)
    }
}
