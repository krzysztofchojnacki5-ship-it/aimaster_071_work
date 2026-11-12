package pl.aimasterpro.app.core

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MemoryMutationGateTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
    }

    @After fun tearDown() {
        context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
    }

    private fun request(
        actor: MemoryGovernance.Actor = MemoryGovernance.Actor.USER,
        approvalRef: String = "ui-confirmation:rule-1"
    ) = MemoryGovernance.Request(
        actor = actor,
        mutation = MemoryGovernance.Mutation.ADD_SHARED_RULE,
        objectId = "rule-1",
        reason = "Użytkownik potwierdził zapis w interfejsie",
        approvalRef = approvalRef
    )

    @Test fun deniedAgentNeverRunsMutationAndIsAudited() {
        var invoked = false
        assertFalse(MemoryMutationGate.execute(context, request(MemoryGovernance.Actor.AGENT)) { _: MemoryMutationPermit ->
            invoked = true
            true
        })
        assertFalse(invoked)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(1, rows.size)
        assertEquals(MemoryAuditLog.Phase.FAILED, rows.single().phase)
        assertEquals("DENIED_AGENT_READ_ONLY", rows.single().outcome)
    }

    @Test fun authorizedMutationRequiresIntentAndCommittedRecords() {
        var invoked = false
        assertTrue(MemoryMutationGate.execute(context, request()) { _: MemoryMutationPermit ->
            invoked = true
            true
        })
        assertTrue(invoked)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(listOf(MemoryAuditLog.Phase.INTENT, MemoryAuditLog.Phase.COMMITTED), rows.map { it.phase })
        assertEquals(listOf("AUTHORIZED", "SUCCESS"), rows.map { it.outcome })
    }

    @Test fun unresolvedIntentBlocksReplayBeforeMutation() {
        val request = request()
        assertTrue(MemoryAuditLog.append(context, MemoryAuditLog.Event(
            actor = request.actor.name,
            mutation = request.mutation.name,
            objectId = request.objectId,
            reason = request.reason,
            approvalRef = request.approvalRef,
            phase = MemoryAuditLog.Phase.INTENT,
            outcome = "AUTHORIZED"
        )))

        var invoked = false
        assertFalse(MemoryMutationGate.execute(context, request) { _: MemoryMutationPermit ->
            invoked = true
            true
        })
        assertFalse(invoked)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(1, rows.size)
        assertEquals(MemoryAuditLog.Phase.INTENT, rows.single().phase)
    }

    @Test fun failedMutationLeavesIntentAndFailureRecord() {
        assertFalse(MemoryMutationGate.execute(context, request()) { _: MemoryMutationPermit -> false })
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(listOf(MemoryAuditLog.Phase.INTENT, MemoryAuditLog.Phase.FAILED), rows.map { it.phase })
        assertEquals("MUTATION_FAILED", rows.last().outcome)
    }

    @Test fun permitForOneMutationCannotBeReusedForAnotherStoreOperation() {
        val request = request()
        var storeMutationResult = true
        assertTrue(MemoryMutationGate.execute(context, request) { permit: MemoryMutationPermit ->
            storeMutationResult = AiMemoryStore.removeMemory(context, "unrelated", permit)
            true
        })
        assertFalse(storeMutationResult)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(MemoryAuditLog.Phase.COMMITTED, rows.last().phase)
    }

    @Test fun deniedAttemptDoesNotMaskAnEarlierUnresolvedIntent() {
        val userRequest = request()
        assertTrue(MemoryAuditLog.append(context, MemoryAuditLog.Event(
            actor = userRequest.actor.name,
            mutation = userRequest.mutation.name,
            objectId = userRequest.objectId,
            reason = userRequest.reason,
            approvalRef = userRequest.approvalRef,
            phase = MemoryAuditLog.Phase.INTENT,
            outcome = "AUTHORIZED"
        )))

        assertFalse(MemoryMutationGate.execute(context, request(MemoryGovernance.Actor.AGENT)) {
            _: MemoryMutationPermit -> error("Denied agent mutation must never run")
        })
        var replayInvoked = false
        assertFalse(MemoryMutationGate.execute(context, userRequest) {
            _: MemoryMutationPermit -> replayInvoked = true; true
        })
        assertFalse(replayInvoked)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(listOf("AUTHORIZED", "DENIED_AGENT_READ_ONLY"), rows.map { it.outcome })
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(context, "USER", "ADD_SHARED_RULE", "rule-1"))
    }

    @Test fun unreadableAuditJournalBlocksMutationAndPreservesOriginalBytes() {
        val prefs = context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE)
        val corrupt = "[{not-valid-json"
        assertTrue(prefs.edit().putString("events", corrupt).commit())

        var invoked = false
        assertFalse(MemoryMutationGate.execute(context, request()) { _: MemoryMutationPermit ->
            invoked = true
            true
        })
        assertFalse(invoked)
        assertEquals(corrupt, prefs.getString("events", null))
        assertEquals(null, MemoryAuditLog.readVerified(context))
    }

    @Test fun missingApprovalBlocksMutationBeforeIntent() {
        var invoked = false
        assertFalse(MemoryMutationGate.execute(context, request(approvalRef = "")) { _: MemoryMutationPermit ->
            invoked = true
            true
        })
        assertFalse(invoked)
        assertEquals("DENIED_USER_APPROVAL_REQUIRED", MemoryAuditLog.readVerified(context)!!.single().outcome)
    }
}
