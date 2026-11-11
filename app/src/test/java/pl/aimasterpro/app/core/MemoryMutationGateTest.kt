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
        assertFalse(MemoryMutationGate.execute(context, request(MemoryGovernance.Actor.AGENT)) {
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
        assertTrue(MemoryMutationGate.execute(context, request()) {
            invoked = true
            true
        })
        assertTrue(invoked)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(listOf(MemoryAuditLog.Phase.INTENT, MemoryAuditLog.Phase.COMMITTED), rows.map { it.phase })
        assertEquals(listOf("AUTHORIZED", "SUCCESS"), rows.map { it.outcome })
    }

    @Test fun failedMutationLeavesIntentAndFailureRecord() {
        assertFalse(MemoryMutationGate.execute(context, request()) { false })
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(listOf(MemoryAuditLog.Phase.INTENT, MemoryAuditLog.Phase.FAILED), rows.map { it.phase })
        assertEquals("MUTATION_FAILED", rows.last().outcome)
    }

    @Test fun missingApprovalBlocksMutationBeforeIntent() {
        var invoked = false
        assertFalse(MemoryMutationGate.execute(context, request(approvalRef = "")) {
            invoked = true
            true
        })
        assertFalse(invoked)
        assertEquals("DENIED_USER_APPROVAL_REQUIRED", MemoryAuditLog.readVerified(context)!!.single().outcome)
    }
}
