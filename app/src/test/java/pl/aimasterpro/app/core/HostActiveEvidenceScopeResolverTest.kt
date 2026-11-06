package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostActiveEvidenceScopeResolverTest {
    private fun candidate(id: String, chat: String = "chat-A", snapshot: String = "snapshot-12345678") =
        MasterEvidenceContextBuilder.Candidate(id, "task-$id", chat, snapshot, 100)

    private fun row(id: String, status: String = "APPLIED_UNVERIFIED", chat: String = "chat-A", snapshot: String = "snapshot-12345678") =
        HostActiveEvidenceScopeResolver.Candidate(candidate(id, chat, snapshot), status)

    @Test fun selectsOnlyTheExactHostSelectedChangeSet() {
        val selected = candidate("change-12345678")
        val result = HostActiveEvidenceScopeResolver.select("chat-A", selected.changeSetId, listOf(
            row("change-12345678"), row("change-87654321", "APPLIED")
        ))
        assertEquals(HostActiveEvidenceScopeResolver.Status.SELECTED, result.status)
        assertEquals(selected, result.selected)
    }

    @Test fun noActiveIdMeansNoneEvenWhenSeveralChangesAreApplied() {
        val result = HostActiveEvidenceScopeResolver.select("chat-A", null, listOf(
            row("change-12345678", "APPLIED"), row("change-87654321")
        ))
        assertEquals(HostActiveEvidenceScopeResolver.Status.NONE, result.status)
        assertNull(result.selected)
    }

    @Test fun refusesAnActiveIdThatBelongsToAnotherChat() {
        val result = HostActiveEvidenceScopeResolver.select("chat-A", "change-12345678", listOf(
            row("change-12345678", "APPLIED", "chat-B")
        ))
        assertEquals(HostActiveEvidenceScopeResolver.Status.INVALID, result.status)
        assertNull(result.selected)
    }

    @Test fun refusesMissingOrDuplicateActiveId() {
        val missing = HostActiveEvidenceScopeResolver.select("chat-A", "missing-12345678", listOf(row("change-12345678")))
        assertEquals(HostActiveEvidenceScopeResolver.Status.INVALID, missing.status)
        val duplicate = HostActiveEvidenceScopeResolver.select("chat-A", "change-12345678", listOf(row("change-12345678"), row("change-12345678")))
        assertEquals(HostActiveEvidenceScopeResolver.Status.INVALID, duplicate.status)
    }

    @Test fun refusesIneligibleStatusAndMissingSnapshot() {
        val rejected = HostActiveEvidenceScopeResolver.select("chat-A", "change-12345678", listOf(row("change-12345678", "ROLLED_BACK")))
        assertEquals(HostActiveEvidenceScopeResolver.Status.INVALID, rejected.status)
        val noSnapshot = HostActiveEvidenceScopeResolver.select("chat-A", "change-12345678", listOf(row("change-12345678", snapshot = "")))
        assertEquals(HostActiveEvidenceScopeResolver.Status.INVALID, noSnapshot.status)
    }

    @Test fun refusesMalformedTaskIdentity() {
        val malformed = row("change-12345678").copy(changeSet = candidate("change-12345678").copy(taskId = ""))
        val result = HostActiveEvidenceScopeResolver.select("chat-A", "change-12345678", listOf(malformed))
        assertEquals(HostActiveEvidenceScopeResolver.Status.INVALID, result.status)
    }
}
