package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterEvidenceContextBuilderTest {
    private class MemoryBackend(var value: String? = null) : VerificationEvidenceBackend {
        override fun read(): String? = value
        override fun write(value: String): Boolean { this.value = value; return true }
    }

    private val candidate = MasterEvidenceContextBuilder.Candidate(
        changeSetId = "changeset-12345678", taskId = "patch-task-12345678", chatId = "chat-1",
        snapshotId = "snapshot-12345678", updatedAt = 100
    )

    private fun resolverWith(vararg rows: VerificationEvidence): VerificationEvidenceResolver {
        val store = VerificationEvidenceStore(MemoryBackend())
        check(store.appendAll(rows.toList()))
        return VerificationEvidenceResolver(store)
    }

    private fun completeEvidence() = listOf(
        VerificationEvidence.runtimeCheck(candidate.taskId, VerificationEvidenceKind.WORKSPACE_INTEGRITY,
            VerificationEvidenceStatus.PASS, "manifest PASS", 10, candidate.chatId, candidate.snapshotId),
        VerificationEvidence.runtimeCheck(candidate.taskId, VerificationEvidenceKind.SNAPSHOT_INTEGRITY,
            VerificationEvidenceStatus.PASS, "snapshot PASS", 11, candidate.chatId, candidate.snapshotId),
        VerificationEvidence.runtimeCheck(candidate.taskId, VerificationEvidenceKind.PATCH_SCOPE,
            VerificationEvidenceStatus.PASS, "scope PASS", 12, candidate.chatId, candidate.snapshotId),
        VerificationEvidence.runtimeCheck(candidate.taskId, VerificationEvidenceKind.PROJECT_TESTS,
            VerificationEvidenceStatus.NOT_RUN, "project tests NOT_RUN", 13, candidate.chatId, candidate.snapshotId)
    )

    @Test fun explicitChangeSetReferenceResolvesOnlyExactScope() {
        val result = MasterEvidenceContextBuilder.build(
            "Sprawdź zmianę ${candidate.changeSetId}", "chat-1", listOf(candidate), resolverWith(*completeEvidence().toTypedArray())
        )
        assertEquals(MasterEvidenceContextBuilder.Status.READY, result.status)
        assertEquals(candidate, result.selected)
        assertEquals(VerificationAssessmentStatus.INCOMPLETE, result.assessment!!.status)
        assertTrue(result.contextText.contains("assessment=INCOMPLETE"))
    }

    @Test fun noReferenceNeverFallsBackToLatestChangeSet() {
        val result = MasterEvidenceContextBuilder.build(
            "Jak działa ten moduł?", "chat-1", listOf(candidate), resolverWith(*completeEvidence().toTypedArray())
        )
        assertEquals(MasterEvidenceContextBuilder.Status.NO_EXPLICIT_REFERENCE, result.status)
        assertNull(result.selected)
        assertNull(result.assessment)
        assertTrue(result.contextText.contains("Nie wybieraj automatycznie najnowszego"))
    }

    @Test fun referenceFromAnotherChatIsIgnored() {
        val result = MasterEvidenceContextBuilder.build(
            "Sprawdź ${candidate.snapshotId}", "chat-2", listOf(candidate), resolverWith(*completeEvidence().toTypedArray())
        )
        assertEquals(MasterEvidenceContextBuilder.Status.NO_EXPLICIT_REFERENCE, result.status)
        assertNull(result.selected)
    }

    @Test fun multipleExplicitReferencesAreRejectedAsAmbiguous() {
        val other = candidate.copy(changeSetId = "changeset-87654321", taskId = "patch-task-87654321", snapshotId = "snapshot-87654321")
        val result = MasterEvidenceContextBuilder.build(
            "Porównaj ${candidate.changeSetId} oraz ${other.changeSetId}", "chat-1", listOf(candidate, other), resolverWith()
        )
        assertEquals(MasterEvidenceContextBuilder.Status.AMBIGUOUS_REFERENCE, result.status)
        assertNull(result.selected)
    }

    @Test fun hostOwnedScopeResolvesWithoutUserPromptReference() {
        val result = MasterEvidenceContextBuilder.buildForHostScope(
            candidate, "chat-1", resolverWith(*completeEvidence().toTypedArray())
        )
        assertEquals(MasterEvidenceContextBuilder.Status.READY, result.status)
        assertEquals(candidate, result.selected)
        assertEquals(VerificationAssessmentStatus.INCOMPLETE, result.assessment!!.status)
    }

    @Test fun hostOwnedScopeRejectsCandidateFromAnotherChat() {
        val result = MasterEvidenceContextBuilder.buildForHostScope(
            candidate, "chat-2", resolverWith(*completeEvidence().toTypedArray())
        )
        assertEquals(MasterEvidenceContextBuilder.Status.INVALID_CANDIDATE, result.status)
        assertNull(result.assessment)
    }

    @Test fun missingScopeIdentifiersAreNotSynthesized() {
        val legacy = candidate.copy(snapshotId = "")
        val result = MasterEvidenceContextBuilder.build(
            "Sprawdź ${candidate.changeSetId}", "chat-1", listOf(legacy), resolverWith()
        )
        assertEquals(MasterEvidenceContextBuilder.Status.INVALID_CANDIDATE, result.status)
        assertFalse(result.contextText.contains("assessment=PASS"))
    }
}
