package pl.aimasterpro.app.data

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.aimasterpro.app.core.CompanionRuntime
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CompanionTrustPersistentLockTest {
    private fun cleanMarkers(context: Context) {
        context.getSharedPreferences("companion_provisioning_v1", Context.MODE_PRIVATE)
            .edit().remove("peer_pin_records_v1").remove("trust_unavailable_after_failed_mutation_v1").commit()
        File(context.noBackupFilesDir, "companion_trust_unavailable.lock").delete()
        val store = CompanionProvisioningStore(context)
        val stateField = CompanionProvisioningStore::class.java.getDeclaredField("processTrustState")
        stateField.isAccessible = true
        val state = stateField.get(store)
        val latchField = state.javaClass.getDeclaredField("unavailableAfterFailedMutation")
        latchField.isAccessible = true
        latchField.setBoolean(state, false)
    }


    private class RecordingTrustIo(
        private val delegate: CompanionTrustIo = AndroidCompanionTrustIo,
        var failFirstCommit: Boolean = false,
        var failAliasDeletion: Boolean = false
    ) : CompanionTrustIo {
        var commitCalls = 0
        var aliasDeleteCalls = 0

        override fun commit(editor: SharedPreferences.Editor): Boolean {
            commitCalls++
            if (failFirstCommit && commitCalls == 1) return false
            return delegate.commit(editor)
        }

        override fun atomicReplace(source: Path, target: Path) = delegate.atomicReplace(source, target)
        override fun deleteIfExists(path: Path): Boolean = delegate.deleteIfExists(path)
        override fun syncDirectory(directory: File) = delegate.syncDirectory(directory)

        override fun deleteKeyAlias(alias: String) {
            aliasDeleteCalls++
            if (failAliasDeletion) error("injected:keystore.deleteEntry")
        }
    }


    private class BlockingCommitTrustIo(
        private val entered: CountDownLatch,
        private val release: CountDownLatch
    ) : CompanionTrustIo {
        private val active = AtomicInteger(0)
        val maxConcurrentCommits = AtomicInteger(0)

        override fun commit(editor: SharedPreferences.Editor): Boolean {
            val now = active.incrementAndGet()
            maxConcurrentCommits.updateAndGet { previous -> maxOf(previous, now) }
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS)) { "commit test barrier timed out" }
                return editor.commit()
            } finally {
                active.decrementAndGet()
            }
        }

        override fun atomicReplace(source: Path, target: Path) = AndroidCompanionTrustIo.atomicReplace(source, target)
        override fun deleteIfExists(path: Path): Boolean = AndroidCompanionTrustIo.deleteIfExists(path)
        override fun syncDirectory(directory: File) = AndroidCompanionTrustIo.syncDirectory(directory)
        override fun deleteKeyAlias(alias: String) = Unit
    }

    private fun invoke(store: CompanionProvisioningStore, method: String, vararg args: Any): Any? {
        val target = CompanionProvisioningStore::class.java.getDeclaredMethod(
            method, *args.map { it.javaClass }.toTypedArray()
        )
        target.isAccessible = true
        return target.invoke(store, *args)
    }

    @Test fun independentNoBackupMarkerBlocksFreshStoreAfterRestart() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        cleanMarkers(context)
        val store = CompanionProvisioningStore(context)
        invoke(store, "writePersistentTrustLockFile")
        val marker = File(context.noBackupFilesDir, "companion_trust_unavailable.lock")
        assertTrue(marker.isFile)

        val restartedStore = CompanionProvisioningStore(context)
        val failure = runCatching { invoke(restartedStore, "readEncryptedPayload") }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any {
            it is CompanionProvisioningRecoveryRequiredException
        })

        assertTrue(invoke(restartedStore, "deletePersistentTrustLockFile") as Boolean)
        assertFalse(marker.exists())
    }


    @Test fun concurrentMarkerWritersUseIndependentTemporaryFiles() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        cleanMarkers(context)
        val directory = context.noBackupFilesDir
        val first = CompanionProvisioningStore(context)
        val second = CompanionProvisioningStore(context)
        val writers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 32).map { index ->
                writers.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    invoke(if (index % 2 == 0) first else second, "writePersistentTrustLockFile")
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            val marker = File(directory, "companion_trust_unavailable.lock")
            assertTrue(marker.isFile)
            assertEquals("AI_MASTER_COMPANION_TRUST_LOCK_V1", marker.readText())
            val leftovers = directory.listFiles().orEmpty().filter {
                it.name.startsWith("companion_trust_unavailable.lock.") && it.name.endsWith(".tmp")
            }
            assertTrue("Temporary lock files must be cleaned", leftovers.isEmpty())
        } finally {
            writers.shutdownNow()
            cleanMarkers(context)
        }
    }

    @Test fun directoryMetadataSyncSupportsMarkerPublicationAndDeletion() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        cleanMarkers(context)
        val store = CompanionProvisioningStore(context)
        val directory = context.noBackupFilesDir

        invoke(store, "writePersistentTrustLockFile")
        invoke(store, "syncPersistentTrustLockDirectory", directory)
        val marker = File(directory, "companion_trust_unavailable.lock")
        assertTrue(marker.isFile)

        assertTrue(invoke(store, "deletePersistentTrustLockFile") as Boolean)
        assertFalse(marker.exists())
        invoke(store, "syncPersistentTrustLockDirectory", directory)
    }


    @Test fun injectedFailuresExposeAtomicMarkerCrashBoundaries() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val publishPoints = listOf(
            "marker.before_atomic_move" to false,
            "marker.after_atomic_move" to true,
            "marker.before_directory_fsync_publish" to true,
            "marker.after_directory_fsync_publish" to true
        )
        publishPoints.forEach { (point, markerExpected) ->
            cleanMarkers(context)
            val store = CompanionProvisioningStore(context)
            store.faultInjector = { current ->
                if (current == point) throw IllegalStateException("injected:$point")
            }
            val failure = runCatching { invoke(store, "writePersistentTrustLockFile") }.exceptionOrNull()
            assertTrue("Expected injected failure at $point", failure != null)
            val marker = File(context.noBackupFilesDir, "companion_trust_unavailable.lock")
            assertEquals("Unexpected marker state at $point", markerExpected, marker.isFile)
            val leftovers = context.noBackupFilesDir.listFiles().orEmpty().filter {
                it.name.startsWith("companion_trust_unavailable.lock.") && it.name.endsWith(".tmp")
            }
            assertTrue("Temporary files leaked at $point", leftovers.isEmpty())
        }
    }

    @Test fun injectedDeleteFailuresDistinguishUnlinkFromDirectoryDurability() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val deletePoints = listOf(
            "marker.before_delete" to true,
            "marker.after_delete" to false,
            "marker.before_directory_fsync_delete" to false,
            "marker.after_directory_fsync_delete" to false
        )
        deletePoints.forEach { (point, markerExpected) ->
            cleanMarkers(context)
            val store = CompanionProvisioningStore(context)
            invoke(store, "writePersistentTrustLockFile")
            store.faultInjector = { current ->
                if (current == point) throw IllegalStateException("injected:$point")
            }
            val failure = runCatching { invoke(store, "deletePersistentTrustLockFile") }.exceptionOrNull()
            assertTrue("Expected injected failure at $point", failure != null)
            val marker = File(context.noBackupFilesDir, "companion_trust_unavailable.lock")
            assertEquals("Unexpected marker state at $point", markerExpected, marker.isFile)
        }
        cleanMarkers(context)
    }

    @Test fun falsePreferencesCommitUsesProductionLockRecoveryPath() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        cleanMarkers(context)
        val io = RecordingTrustIo(failFirstCommit = true)
        val store = CompanionProvisioningStore(context, io)

        val failure = runCatching { store.wipeAllTrust() }.exceptionOrNull()
        assertTrue("wipeAllTrust must report a false SharedPreferences commit", failure != null)
        assertTrue("recovery marker commit should be attempted after the failed wipe", io.commitCalls >= 2)
        assertTrue(context.getSharedPreferences("companion_provisioning_v1", Context.MODE_PRIVATE)
            .getBoolean("trust_unavailable_after_failed_mutation_v1", false))
        assertTrue(File(context.noBackupFilesDir, "companion_trust_unavailable.lock").isFile)
        assertTrue(io.aliasDeleteCalls >= 1)

        // Remove both durable markers to prove that a second store instance still sees the
        // process-wide latch, not merely the persistent marker files.
        context.getSharedPreferences("companion_provisioning_v1", Context.MODE_PRIVATE)
            .edit().remove("trust_unavailable_after_failed_mutation_v1").commit()
        File(context.noBackupFilesDir, "companion_trust_unavailable.lock").delete()
        val restartedStore = CompanionProvisioningStore(context)
        val readFailure = runCatching { invoke(restartedStore, "readEncryptedPayload") }.exceptionOrNull()
        assertTrue(generateSequence(readFailure) { it.cause }.any {
            it is CompanionProvisioningRecoveryRequiredException
        })
        cleanMarkers(context)
    }

    @Test fun keystoreAliasDeletionFailureIsSurfacedAfterCommittedWipe() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        cleanMarkers(context)
        val io = RecordingTrustIo(failAliasDeletion = true)
        val store = CompanionProvisioningStore(context, io)

        val failure = runCatching { store.wipeAllTrust() }.exceptionOrNull()
        assertTrue("Committed wipe must surface a Keystore deletion failure", failure is CompanionTrustRevocationException)
        assertEquals(1, io.aliasDeleteCalls)
        assertFalse(context.getSharedPreferences("companion_provisioning_v1", Context.MODE_PRIVATE)
            .getBoolean("trust_unavailable_after_failed_mutation_v1", false))
        assertFalse(File(context.noBackupFilesDir, "companion_trust_unavailable.lock").exists())
        cleanMarkers(context)
    }

    @Test fun destructiveMutationsSerializeAcrossStoreInstancesInOneProcess() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        cleanMarkers(context)
        val firstCommitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val secondCallStarted = CountDownLatch(1)
        val io = BlockingCommitTrustIo(firstCommitEntered, releaseCommit)
        val first = CompanionProvisioningStore(context, io)
        val second = CompanionProvisioningStore(context, io)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val one = workers.submit { first.wipeAllTrust() }
            assertTrue(firstCommitEntered.await(3, TimeUnit.SECONDS))
            val two = workers.submit {
                secondCallStarted.countDown()
                second.wipeAllTrust()
            }
            assertTrue(secondCallStarted.await(3, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertEquals("Different store instances must share one process trust monitor", 1,
                io.maxConcurrentCommits.get())
            releaseCommit.countDown()
            one.get(5, TimeUnit.SECONDS)
            two.get(5, TimeUnit.SECONDS)
        } finally {
            releaseCommit.countDown()
            workers.shutdownNow()
            cleanMarkers(context)
        }
    }


    @Test fun mutationThroughSecondStoreFencesRuntimeBoundToFirstStore() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        cleanMarkers(context)
        val first = CompanionProvisioningStore(context, RecordingTrustIo())
        val second = CompanionProvisioningStore(context, RecordingTrustIo())
        val runtime = CompanionRuntime.create(first)
        val handle = object : Closeable {
            var closeCount = 0
            override fun close() { closeCount++ }
        }
        try {
            assertTrue(runtime.registry.register(runtime.registry.ticket("peer-shared"), handle))
            // The second store instance shares the process trust publisher with the first.
            second.wipeAllTrust()
            assertEquals(0, runtime.registry.liveCount())
            assertEquals(1, handle.closeCount)
        } finally {
            runtime.close()
            cleanMarkers(context)
        }
    }

    @Test fun preferencesMarkerIndependentlyBlocksTrustRead() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        cleanMarkers(context)
        context.getSharedPreferences("companion_provisioning_v1", Context.MODE_PRIVATE)
            .edit().putBoolean("trust_unavailable_after_failed_mutation_v1", true).commit()

        val store = CompanionProvisioningStore(context)
        val failure = runCatching { invoke(store, "readEncryptedPayload") }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any {
            it is CompanionProvisioningRecoveryRequiredException
        })
    }
}
