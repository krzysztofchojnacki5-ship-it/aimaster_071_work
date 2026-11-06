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
}
