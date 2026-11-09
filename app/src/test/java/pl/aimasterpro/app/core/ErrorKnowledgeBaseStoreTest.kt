package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ErrorKnowledgeBaseStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("aiforteca_error_knowledge_v1", Context.MODE_PRIVATE)

    @Before fun reset() {
        SharedPreferencesWriteHealth.resetForTests("aiforteca_error_knowledge_v1")
        prefs.edit().clear().commit()
    }

    private fun record(
        recordId: String,
        status: ErrorKnowledgeBaseStore.CaseStatus = ErrorKnowledgeBaseStore.CaseStatus.OPEN,
        supersedes: String = "",
        redacted: Boolean = true
    ) = ErrorKnowledgeBaseStore.Record(
        recordId = recordId, errorId = "error-1", timestampUtc = 1_700_000_000_000L,
        project = "AiFORTeca", repository = "owner/repo", branch = "work/aiforteca",
        commit = "", environment = "JDK 17; Gradle 8.13",
        operation = "compileDebugKotlin", exactCommand = "./gradlew assembleDebug",
        redactedErrorOutput = "e: unresolved reference: sample",
        reproductionSteps = "Run the documented build command",
        reproducibility = "DOCUMENTED", observedFacts = listOf("Compiler reports unresolved reference"),
        hypotheses = listOf("Missing import"), confirmedRootCause = if (status == ErrorKnowledgeBaseStore.CaseStatus.ROOT_CAUSE_CONFIRMED ||
            status == ErrorKnowledgeBaseStore.CaseStatus.FIXED_UNVERIFIED ||
            status == ErrorKnowledgeBaseStore.CaseStatus.VERIFIED) "Missing declaration in source set" else "",
        attemptedFixes = emptyList(), linkedTaskIds = listOf("task-1"),
        linkedCommits = emptyList(), linkedTests = emptyList(), evidenceRefs = listOf("log:build-1"),
        status = status, redactionConfirmed = redacted,
        fixSummary = if (status == ErrorKnowledgeBaseStore.CaseStatus.FIXED_UNVERIFIED ||
            status == ErrorKnowledgeBaseStore.CaseStatus.VERIFIED) "Added required declaration" else "",
        verificationEnvironment = if (status == ErrorKnowledgeBaseStore.CaseStatus.VERIFIED) "JDK 17 / Gradle 8.13" else "",
        supersedesRecordId = supersedes
    ).let {
        if (status == ErrorKnowledgeBaseStore.CaseStatus.FIXED_UNVERIFIED ||
            status == ErrorKnowledgeBaseStore.CaseStatus.VERIFIED) it.copy(linkedCommits = listOf("abcdef123456"))
        else if (status == ErrorKnowledgeBaseStore.CaseStatus.VERIFIED) it.copy(linkedTests = listOf("test:1"))
        else it
    }.let {
        if (status == ErrorKnowledgeBaseStore.CaseStatus.VERIFIED) it.copy(linkedTests = listOf("test:1"))
        else it
    }

    @Test fun appendsImmutableStatusHistoryAndReturnsLatestRecord() {
        assertTrue(ErrorKnowledgeBaseStore.append(context, record("r1")))
        assertTrue(ErrorKnowledgeBaseStore.append(context, record(
            "r2", ErrorKnowledgeBaseStore.CaseStatus.INVESTIGATING, "r1"
        )))
        val snapshot = ErrorKnowledgeBaseStore.snapshot(context)
        assertEquals(ErrorKnowledgeBaseStore.ReadStatus.AVAILABLE, snapshot.status)
        assertEquals(listOf("r2", "r1"), snapshot.records.map { it.recordId })
        assertEquals("r2", ErrorKnowledgeBaseStore.latestForError(context, "error-1")?.recordId)
        assertEquals(ErrorKnowledgeBaseStore.CaseStatus.OPEN, snapshot.records.last().status)
    }

    @Test fun requiresExplicitRedactionConfirmation() {
        assertFalse(ErrorKnowledgeBaseStore.append(context, record("r1", redacted = false)))
        assertTrue(ErrorKnowledgeBaseStore.snapshot(context).records.isEmpty())
    }

    @Test fun rejectsDuplicateIdsAndUnknownOrCrossCaseLinks() {
        assertTrue(ErrorKnowledgeBaseStore.append(context, record("r1")))
        assertFalse(ErrorKnowledgeBaseStore.append(context, record("r1")))
        assertFalse(ErrorKnowledgeBaseStore.append(context, record(
            "r2", ErrorKnowledgeBaseStore.CaseStatus.INVESTIGATING, "missing"
        )))
        val otherCase = record("r3", ErrorKnowledgeBaseStore.CaseStatus.INVESTIGATING, "r1")
            .copy(errorId = "different-error")
        assertFalse(ErrorKnowledgeBaseStore.append(context, otherCase))
    }


    @Test fun rejectsForkedStatusHistory() {
        assertTrue(ErrorKnowledgeBaseStore.append(context, record("r1")))
        assertTrue(ErrorKnowledgeBaseStore.append(context, record(
            "r2", ErrorKnowledgeBaseStore.CaseStatus.INVESTIGATING, "r1"
        )))
        assertFalse(ErrorKnowledgeBaseStore.append(context, record(
            "r3", ErrorKnowledgeBaseStore.CaseStatus.INVESTIGATING, "r1"
        )))
    }

    @Test fun rejectsIllegalTransitionsAndUnverifiedVerifiedClaims() {
        assertTrue(ErrorKnowledgeBaseStore.append(context, record("r1")))
        assertFalse(ErrorKnowledgeBaseStore.append(context, record(
            "r2", ErrorKnowledgeBaseStore.CaseStatus.VERIFIED, "r1"
        )))
        assertFalse(ErrorKnowledgeBaseStore.append(context, record(
            "r3", ErrorKnowledgeBaseStore.CaseStatus.FIXED_UNVERIFIED, "r1"
        )))
    }

    @Test fun corruptHistoryIsPreservedAndNotReportedAsEmpty() {
        val raw = "{broken-json"
        assertTrue(prefs.edit().putString("records", raw).commit())
        assertEquals(ErrorKnowledgeBaseStore.ReadStatus.CORRUPT,
            ErrorKnowledgeBaseStore.snapshot(context).status)
        assertFalse(ErrorKnowledgeBaseStore.append(context, record("r1")))
        assertEquals(raw, prefs.getString("records", null))
    }

    @Test fun verifiedRequiresCommitTestAndVerificationEvidence() {
        assertTrue(ErrorKnowledgeBaseStore.append(context, record("r1")))
        assertTrue(ErrorKnowledgeBaseStore.append(context, record(
            "r2", ErrorKnowledgeBaseStore.CaseStatus.INVESTIGATING, "r1"
        )))
        assertTrue(ErrorKnowledgeBaseStore.append(context, record(
            "r3", ErrorKnowledgeBaseStore.CaseStatus.ROOT_CAUSE_CONFIRMED, "r2"
        )))
        assertTrue(ErrorKnowledgeBaseStore.append(context, record(
            "r4", ErrorKnowledgeBaseStore.CaseStatus.FIXED_UNVERIFIED, "r3"
        )))
        val verified = record("r5", ErrorKnowledgeBaseStore.CaseStatus.VERIFIED, "r4")
            .copy(linkedTests = listOf("test:regression"))
        assertTrue(ErrorKnowledgeBaseStore.append(context, verified))
        assertEquals("r5", ErrorKnowledgeBaseStore.latestForError(context, "error-1")?.recordId)
    }

    @Test fun quarantinedStorageIsUnavailableNotEmpty() {
        SharedPreferencesWriteHealth.commit("aiforteca_error_knowledge_v1",
            object : android.content.SharedPreferences.Editor by prefs.edit() {
                override fun commit(): Boolean = false
            })
        assertEquals(ErrorKnowledgeBaseStore.ReadStatus.UNAVAILABLE,
            ErrorKnowledgeBaseStore.snapshot(context).status)
        assertFalse(ErrorKnowledgeBaseStore.append(context, record("r1")))
    }
}
