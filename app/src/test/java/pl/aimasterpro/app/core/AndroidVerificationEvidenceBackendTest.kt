package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidVerificationEvidenceBackendTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SharedPreferencesWriteHealth.resetForTests("verification_evidence_v1")
        context.getSharedPreferences("verification_evidence_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun androidBackendPersistsEvidenceThroughSharedPreferenceAdapter() {
        val store = VerificationEvidenceStore(AndroidVerificationEvidenceBackend(context))
        val evidence = VerificationEvidence.runtimeCheck(
            "task-android-backend", VerificationEvidenceKind.UNIT_TESTS,
            VerificationEvidenceStatus.PASS, "backend persistence verified", 100L
        )

        assertTrue(store.appendAll(listOf(evidence)))
        assertTrue(store.load().valid)
        assertTrue(store.load().entries.contains(evidence))
    }

    @Test fun ambiguousAndroidBackendCommitQuarantinesReadsAndFurtherWrites() {
        // Seed a valid ledger first; otherwise appendAll correctly rejects corrupt input
        // before reaching commit(), and the ambiguous-commit adapter is never exercised.
        val seedStore = VerificationEvidenceStore(AndroidVerificationEvidenceBackend(context))
        val seed = VerificationEvidence.runtimeCheck(
            "task-ambiguous-seed", VerificationEvidenceKind.UNIT_TESTS,
            VerificationEvidenceStatus.PASS, "valid baseline before ambiguous commit", 150L
        )
        assertTrue(seedStore.appendAll(listOf(seed)))
        val ambiguousPreferences = AmbiguousCommitContext(context, "verification_evidence_v1")
        // Keep the wrapper visible through applicationContext so the backend exercises the
        // same failing SharedPreferences adapter rather than bypassing it via the base context.
        val failingContext = object : android.content.ContextWrapper(ambiguousPreferences) {
            override fun getApplicationContext(): Context = this
        }
        val store = VerificationEvidenceStore(AndroidVerificationEvidenceBackend(failingContext))
        val evidence = VerificationEvidence.runtimeCheck(
            "task-ambiguous-backend", VerificationEvidenceKind.UNIT_TESTS,
            VerificationEvidenceStatus.PASS, "must not be reported as durable", 200L
        )

        assertFalse(store.appendAll(listOf(evidence)))
        assertFalse("Quarantined backend reads must not appear valid", store.load().valid)
        assertFalse("Quarantined backend must reject further writes", store.appendAll(listOf(evidence)))
        assertTrue(runCatching {
            SharedPreferencesWriteHealth.withHealthyAccess("verification_evidence_v1") { true }
        }.isFailure)
    }
    @Test fun androidBackendHoldsSharedPreferenceLockForWholeLedgerTransaction() {
        val store = VerificationEvidenceStore(AndroidVerificationEvidenceBackend(context))
        val evidence = VerificationEvidence.runtimeCheck(
            "task-locking-backend", VerificationEvidenceKind.UNIT_TESTS,
            VerificationEvidenceStatus.PASS, "transaction lock verified", 300L
        )
        val ready = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            ready.countDown()
            result.set(store.appendAll(listOf(evidence)))
            finished.countDown()
        }
        SharedPreferencesWriteHealth.withHealthyAccess("verification_evidence_v1") {
            worker.start()
            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("Evidence transaction must wait on the shared preference lock",
                finished.await(150, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
        worker.join(3_000L)
        assertTrue(finished.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(result.get())
        assertTrue(store.load().entries.contains(evidence))
    }

}
