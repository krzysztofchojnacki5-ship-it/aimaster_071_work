package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationEvidenceResolverTest {
    private class MemoryBackend(var value: String? = null) : VerificationEvidenceBackend {
        override fun read(): String? = value
        override fun write(value: String): Boolean { this.value = value; return true }
    }

    private val required = setOf(VerificationEvidenceKind.UNIT_TESTS)

    private fun resolverWith(vararg rows: VerificationEvidence): VerificationEvidenceResolver {
        val store = VerificationEvidenceStore(MemoryBackend())
        check(store.appendAll(rows.toList()))
        return VerificationEvidenceResolver(store)
    }

    private fun start(executionId: String, time: Long = 10) = VerificationEvidence.notRun(
        "task-1", VerificationEvidenceKind.UNIT_TESTS, "host invocation started", time,
        "chat-1", "snapshot-1", executionId
    )

    private fun result(executionId: String, status: VerificationEvidenceStatus, time: Long = 20) =
        VerificationEvidence.toolExecution(
            "task-1", VerificationEvidenceKind.UNIT_TESTS, status, "host result", executionId,
            time, "chat-1", "snapshot-1"
        )

    @Test fun exactScopeReturnsOnlyMatchingTaskChatAndSnapshot() {
        val wanted = VerificationEvidence.runtimeCheck(
            "task-1", VerificationEvidenceKind.UNIT_TESTS, VerificationEvidenceStatus.PASS,
            "local check", 10, "chat-1", "snapshot-1"
        )
        val otherTask = wanted.copy(id = "other-task", taskId = "task-2")
        val otherChat = wanted.copy(id = "other-chat", chatId = "chat-2")
        val otherSnapshot = wanted.copy(id = "other-snapshot", snapshotId = "snapshot-2")
        val bundle = resolverWith(wanted, otherTask, otherChat, otherSnapshot)
            .resolve("task-1", "chat-1", "snapshot-1", required)
        assertEquals(VerificationAssessmentStatus.PASS, bundle.assessment!!.status)
        assertEquals(listOf(wanted), bundle.verifiedEvidence)
    }

    @Test fun agentClaimIsSeparatedAndCannotSatisfyRequiredCheck() {
        val claim = VerificationEvidence.agentClaim(
            "task-1", VerificationEvidenceKind.UNIT_TESTS, "agent says tests passed", 30,
            "chat-1", "snapshot-1"
        )
        val bundle = resolverWith(claim).resolve("task-1", "chat-1", "snapshot-1", required)
        assertEquals(listOf(claim), bundle.agentClaims)
        assertTrue(bundle.verifiedEvidence.isEmpty())
        assertEquals(VerificationAssessmentStatus.NOT_RUN, bundle.assessment!!.status)
    }

    @Test fun unpairedToolResultIsRejectedAndCannotSatisfyRequiredCheck() {
        val orphan = result("host-run-orphan", VerificationEvidenceStatus.PASS)
        val bundle = resolverWith(orphan).resolve("task-1", "chat-1", "snapshot-1", required)
        assertEquals(1, bundle.rejectedUnpairedExecutionCount)
        assertTrue(bundle.verifiedEvidence.isEmpty())
        assertEquals(VerificationAssessmentStatus.NOT_RUN, bundle.assessment!!.status)
    }

    @Test fun pairedHostExecutionPassIsAccepted() {
        val started = start("host-run-1")
        val completed = result("host-run-1", VerificationEvidenceStatus.PASS)
        val bundle = resolverWith(started, completed)
            .resolve("task-1", "chat-1", "snapshot-1", required)
        assertEquals(0, bundle.rejectedUnpairedExecutionCount)
        assertEquals(VerificationAssessmentStatus.PASS, bundle.assessment!!.status)
        assertEquals(VerificationEvidenceStatus.PASS, bundle.latestByKind[VerificationEvidenceKind.UNIT_TESTS]!!.status)
    }

    @Test fun latestAbandonedInvocationOverridesAnEarlierPass() {
        val firstStart = start("host-run-1", 10)
        val firstPass = result("host-run-1", VerificationEvidenceStatus.PASS, 20)
        val secondStart = start("host-run-2", 30)
        val bundle = resolverWith(firstStart, firstPass, secondStart)
            .resolve("task-1", "chat-1", "snapshot-1", required)
        assertEquals(VerificationAssessmentStatus.INCOMPLETE, bundle.assessment!!.status)
        assertEquals(VerificationEvidenceStatus.NOT_RUN,
            bundle.latestByKind[VerificationEvidenceKind.UNIT_TESTS]!!.status)
    }

    @Test fun laterFailedRunOverridesEarlierPass() {
        val bundle = resolverWith(
            start("host-run-1", 10), result("host-run-1", VerificationEvidenceStatus.PASS, 20),
            start("host-run-2", 30), result("host-run-2", VerificationEvidenceStatus.FAIL, 40)
        ).resolve("task-1", "chat-1", "snapshot-1", required)
        assertEquals(VerificationAssessmentStatus.FAIL, bundle.assessment!!.status)
    }

    @Test fun corruptLedgerFailsClosedWithoutEvidence() {
        val resolver = VerificationEvidenceResolver(VerificationEvidenceStore(MemoryBackend("CORRUPT")))
        val bundle = resolver.resolve("task-1", "chat-1", "snapshot-1", required)
        assertEquals(VerificationEvidenceResolver.ResolutionStatus.LEDGER_INVALID, bundle.status)
        assertFalse(bundle.isUsable)
        assertTrue(bundle.verifiedEvidence.isEmpty())
    }

    @Test fun blankScopeOrRequirementsAreRejected() {
        val resolver = resolverWith()
        val blankScope = resolver.resolve("task-1", "", "snapshot-1", required)
        assertEquals(VerificationEvidenceResolver.ResolutionStatus.INVALID_REQUEST, blankScope.status)
        val noRequirements = resolver.resolve("task-1", "chat-1", "snapshot-1", emptySet())
        assertEquals(VerificationEvidenceResolver.ResolutionStatus.INVALID_REQUEST, noRequirements.status)
    }

    @Test fun executionStartMustMatchKindAndPrecedeItsResult() {
        val wrongKindStart = start("host-run-mismatch").copy(kind = VerificationEvidenceKind.COMPILATION)
        val resultWithWrongKindStart = result("host-run-mismatch", VerificationEvidenceStatus.PASS)
        val wrongOrderStart = start("host-run-order", 30)
        val resultBeforeStart = result("host-run-order", VerificationEvidenceStatus.PASS, 20)
        val bundle = resolverWith(wrongKindStart, resultWithWrongKindStart, wrongOrderStart, resultBeforeStart)
            .resolve("task-1", "chat-1", "snapshot-1", required)
        assertEquals(2, bundle.rejectedUnpairedExecutionCount)
        assertEquals(VerificationAssessmentStatus.INCOMPLETE, bundle.assessment!!.status)
    }

    @Test fun missingRequiredKindsRemainExplicitlyMissing() {
        val bundle = resolverWith().resolve(
            "task-1", "chat-1", "snapshot-1",
            setOf(VerificationEvidenceKind.UNIT_TESTS, VerificationEvidenceKind.ANDROID_BUILD)
        )
        assertEquals(VerificationAssessmentStatus.NOT_RUN, bundle.assessment!!.status)
        assertEquals(2, bundle.assessment.missingKinds.size)
    }
}
