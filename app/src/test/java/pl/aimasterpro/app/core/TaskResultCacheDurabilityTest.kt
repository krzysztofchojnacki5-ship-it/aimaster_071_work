package pl.aimasterpro.app.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TaskResultCacheDurabilityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("task_result_cache_v1", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("task_result_cache_v1")
    }

    @Test fun ambiguousCacheCommitQuarantinesFurtherReadsAndWrites() {
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val delegate = super.getSharedPreferences(name, mode)
                if (name != "task_result_cache_v1") return delegate
                return object : SharedPreferences by delegate {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = delegate.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String, value: String): SharedPreferences.Editor {
                                editor.putString(key, value)
                                return this
                            }
                            override fun commit(): Boolean = false
                        }
                    }
                }
            }
        }

        assertFalse(TaskResultCache.put(failingContext, "fingerprint-a", "report-a", 100L))
        assertTrue(SharedPreferencesWriteHealth.isUncertain("task_result_cache_v1"))
        assertNull(TaskResultCache.get(context, "fingerprint-a", 101L))
        assertFalse(TaskResultCache.put(context, "fingerprint-b", "report-b", 102L))
    }

    @Test fun cacheRoundTripRemainsAvailableAfterHealthyDurableCommit() {
        assertTrue(TaskResultCache.put(context, "fingerprint-roundtrip", "saved report", 200L))
        assertEquals(TaskResultCache.Cached("saved report", 200L),
            TaskResultCache.get(context, "fingerprint-roundtrip", 201L))
    }
}
