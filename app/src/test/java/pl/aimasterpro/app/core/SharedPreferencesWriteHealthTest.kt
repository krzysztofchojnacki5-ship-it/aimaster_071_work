package pl.aimasterpro.app.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesWriteHealthTest {
    @Test fun readCannotPassAnInFlightCommitThatEndsAmbiguously() {
        val name = "shared_preferences_health_race_test"
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        SharedPreferencesWriteHealth.resetForTests(name)

        val commitEntered = CountDownLatch(1)
        val allowCommitToFinish = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val commitResult = AtomicBoolean(true)
        val readRejected = AtomicBoolean(false)

        val failingEditor = object : SharedPreferences.Editor by prefs.edit().putString("state", "uncertain") {
            override fun commit(): Boolean {
                commitEntered.countDown()
                allowCommitToFinish.await(2, TimeUnit.SECONDS)
                return false
            }
        }

        val writer = Thread {
            commitResult.set(SharedPreferencesWriteHealth.commit(name, failingEditor))
        }
        val reader = Thread {
            try {
                SharedPreferencesWriteHealth.withHealthyAccess(name) {
                    prefs.getString("state", null)
                }
            } catch (_: IllegalStateException) {
                readRejected.set(true)
            } finally {
                readFinished.countDown()
            }
        }

        try {
            writer.start()
            assertTrue("writer did not enter commit", commitEntered.await(2, TimeUnit.SECONDS))
            reader.start()
            assertFalse("read must wait while commit outcome is unknown", readFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            allowCommitToFinish.countDown()
        }

        assertTrue("reader did not finish after commit outcome", readFinished.await(2, TimeUnit.SECONDS))
        writer.join(2_000)
        reader.join(2_000)
        assertFalse(commitResult.get())
        assertTrue("read must be rejected after ambiguous commit", readRejected.get())
        SharedPreferencesWriteHealth.resetForTests(name)
    }
}
