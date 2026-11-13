package pl.aimasterpro.app.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.MessageDigest

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

    private fun seedCommittedJournal(count: Int) {
        val rows = JSONArray()
        var previousHash = "GENESIS"
        for (sequence in 1..count) {
            val actor = "SYSTEM_RECOVERY"
            val mutation = "SEED"
            val objectId = "seed-$sequence"
            val reason = "seed record"
            val approvalRef = ""
            val phase = MemoryAuditLog.Phase.COMMITTED.name
            val outcome = "SEED"
            val timestamp = 1_800_000_000_000L + sequence
            val values = listOf(
                sequence.toString(), actor, mutation, objectId, reason, approvalRef,
                phase, outcome, timestamp.toString(), previousHash
            )
            val canonical = values.joinToString("") { "${it.length}:$it" }
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            rows.put(JSONObject()
                .put("sequence", sequence.toLong())
                .put("actor", actor)
                .put("mutation", mutation)
                .put("objectId", objectId)
                .put("reason", reason)
                .put("approvalRef", approvalRef)
                .put("phase", phase)
                .put("outcome", outcome)
                .put("timestamp", timestamp)
                .put("previousHash", previousHash)
                .put("hash", hash))
            previousHash = hash
        }
        assertTrue(context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE)
            .edit().putString("events", rows.toString()).commit())
    }

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

    @Test fun replayOfCommittedApprovalReferenceIsRejectedWithoutMutation() {
        val approvedRequest = request()
        var firstInvoked = false
        assertTrue(MemoryMutationGate.execute(context, approvedRequest) { _: MemoryMutationPermit ->
            firstInvoked = true
            true
        })
        assertTrue(firstInvoked)

        var replayInvoked = false
        assertFalse(MemoryMutationGate.execute(context, approvedRequest) { _: MemoryMutationPermit ->
            replayInvoked = true
            true
        })
        assertFalse(replayInvoked)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(listOf("AUTHORIZED", "SUCCESS", "DENIED_DUPLICATE_REQUEST"), rows.map { it.outcome })
    }

    @Test fun distinctApprovalReferenceCanAuthorizeANewMutationForSameObject() {
        assertTrue(MemoryMutationGate.execute(context, request(approvalRef = "ui-confirmation:first")) { _: MemoryMutationPermit -> true })
        assertTrue(MemoryMutationGate.execute(context, request(approvalRef = "ui-confirmation:second")) { _: MemoryMutationPermit -> true })
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(4, rows.size)
        assertEquals(2, rows.count { it.phase == MemoryAuditLog.Phase.INTENT })
        assertEquals(2, rows.count { it.phase == MemoryAuditLog.Phase.COMMITTED })
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

    @Test fun partialMutationFailureRemainsUnresolvedAndCannotBeReplayed() {
        var durableSideEffectCount = 0
        val approvedRequest = request()

        assertFalse(MemoryMutationGate.execute(context, approvedRequest) { _: MemoryMutationPermit ->
            // Model a callback that persisted one store before a later store failed.
            durableSideEffectCount++
            false
        })
        assertEquals(1, durableSideEffectCount)
        assertTrue(MemoryAuditLog.hasUnresolvedIntent(
            context, "USER", "ADD_SHARED_RULE", "rule-1"
        ))

        assertFalse(MemoryMutationGate.execute(context, approvedRequest) { _: MemoryMutationPermit ->
            durableSideEffectCount++
            true
        })
        assertEquals("A failed partial mutation must not be replayed blindly.", 1, durableSideEffectCount)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(MemoryAuditLog.Phase.FAILED, rows.last().phase)
        assertEquals("MUTATION_FAILED", rows.last().outcome)
    }

    @Test fun permitCannotMutateSharedMemoryOutsideItsAuthorizedObjectId() {
        val request = request()
        var storeMutationResult = true
        assertFalse(MemoryMutationGate.execute(context, request) { permit: MemoryMutationPermit ->
            storeMutationResult = AiMemoryStore.addMemory(
                context, "unrelated", permit, objectId = "different-rule"
            )
            storeMutationResult
        })
        assertFalse(storeMutationResult)
        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(MemoryAuditLog.Phase.FAILED, rows.last().phase)
        assertEquals("MUTATION_FAILED", rows.last().outcome)
        assertFalse(AiMemoryStore.containsExactMemory(context, "unrelated"))
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
    @Test fun terminalAuditAppendFailureNeverReportsSuccessOrReplaysMutation() {
        for (persistTerminalBeforeReturningFalse in listOf(false, true)) {
            context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE)
                .edit().clear().commit()
            SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")

            var commitCalls = 0
            val failingContext = object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val delegate = context.getSharedPreferences(name, mode)
                    if (name != "ai_memory_audit_log_v1") return delegate
                    return object : SharedPreferences by delegate {
                        override fun edit(): SharedPreferences.Editor {
                            val delegateEditor = delegate.edit()
                            return object : SharedPreferences.Editor by delegateEditor {
                                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                                    delegateEditor.putString(key, value)
                                    return this
                                }

                                override fun commit(): Boolean {
                                    commitCalls++
                                    if (commitCalls == 2) {
                                        if (persistTerminalBeforeReturningFalse) delegateEditor.commit()
                                        return false
                                    }
                                    return delegateEditor.commit()
                                }
                            }
                        }
                    }
                }
            }

            val approvedRequest = request(approvalRef = "terminal-fault:$persistTerminalBeforeReturningFalse")
            var mutationCalls = 0
            assertFalse(MemoryMutationGate.execute(failingContext, approvedRequest) {
                _: MemoryMutationPermit ->
                mutationCalls++
                true
            })
            assertEquals(1, mutationCalls)
            assertTrue(SharedPreferencesWriteHealth.isUncertain("ai_memory_audit_log_v1"))

            // Model process restart: quarantine is process-local, journal bytes are durable.
            SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
            val rows = MemoryAuditLog.readVerified(context)!!
            if (persistTerminalBeforeReturningFalse) {
                assertEquals(listOf("AUTHORIZED", "SUCCESS"), rows.map { it.outcome })
                assertFalse(MemoryAuditLog.hasUnresolvedIntent(
                    context, "USER", "ADD_SHARED_RULE", "rule-1"
                ))
            } else {
                assertEquals(listOf("AUTHORIZED"), rows.map { it.outcome })
                assertTrue(MemoryAuditLog.hasUnresolvedIntent(
                    context, "USER", "ADD_SHARED_RULE", "rule-1"
                ))
            }

            // Neither a missing terminal nor a persisted terminal may replay the mutation.
            assertFalse(MemoryMutationGate.execute(context, approvedRequest) {
                _: MemoryMutationPermit ->
                mutationCalls++
                true
            })
            assertEquals(1, mutationCalls)
        }
    }

    @Test fun mutationIsNotInvokedWhenJournalCannotReserveFailureAndRecoverySlots() {
        seedCommittedJournal(4_998)
        var invoked = false

        assertFalse(MemoryMutationGate.execute(context, request()) { _: MemoryMutationPermit ->
            invoked = true
            true
        })
        assertFalse("Mutation must not run without guaranteed audit completion capacity", invoked)
        assertEquals(4_998, MemoryAuditLog.readVerified(context)!!.size)
    }

}
