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
        val prefs = context.getSharedPreferences("verification_evidence_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("ledger", "original-ledger").commit())
        val failingContext = AmbiguousCommitContext(context, "verification_evidence_v1")
        val store = VerificationEvidenceStore(AndroidVerificationEvidenceBackend(failingContext))
        val evidence = VerificationEvidence.runtimeCheck(
            "task-ambiguous-backend", VerificationEvidenceKind.UNIT_TESTS,
            VerificationEvidenceStatus.PASS, "must not be reported as durable", 200L
        )

        assertFalse(store.appendAll(listOf(evidence)))
        assertFalse("Quarantined backend reads must not appear valid", store.load().valid)
        assertFalse("Quarantined backend must reject further writes", store.appendAll(listOf(evidence)))
        assertFalse(SharedPreferencesWriteHealth.withHealthyAccess("verification_evidence_v1") {
            false
        })
    }
}
