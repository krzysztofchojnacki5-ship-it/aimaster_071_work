package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class MemoryAuditLogTest {
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

    private fun event(phase: MemoryAuditLog.Phase, outcome: String) = MemoryAuditLog.Event(
        actor = "USER",
        mutation = "APPROVE_PROPOSAL",
        objectId = "rule-1",
        reason = "Jawne zatwierdzenie w interfejsie",
        approvalRef = "proposal:rule-1",
        phase = phase,
        outcome = outcome,
        timestamp = 1_800_000_000_000L
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

    @Test fun appendPersistsOrderedHashChainedEvents() {
        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "PREPARED")))
        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.COMMITTED, "SUCCESS")))

        val rows = MemoryAuditLog.readVerified(context)
        assertNotNull(rows)
        assertEquals(2, rows!!.size)
        assertEquals(listOf(1L, 2L), rows.map { it.sequence })
        assertEquals(MemoryAuditLog.Phase.INTENT, rows[0].phase)
        assertEquals(MemoryAuditLog.Phase.COMMITTED, rows[1].phase)
        assertEquals(rows[0].hash, rows[1].previousHash)
        assertTrue(rows.all { it.hash.length == 64 })
    }

    @Test fun tamperedRecordInvalidatesJournalAndBlocksFurtherAppend() {
        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "PREPARED")))
        val prefs = context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE)
        val raw = prefs.getString("events", null)!!
        prefs.edit().putString("events", raw.replace("Jawne zatwierdzenie", "Fałszywe zatwierdzenie")).commit()

        assertNull(MemoryAuditLog.readVerified(context))
        assertFalse(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.COMMITTED, "SUCCESS")))
    }

    @Test fun malformedEventIsRejectedWithoutWriting() {
        val invalid = event(MemoryAuditLog.Phase.INTENT, " ")
        assertFalse(MemoryAuditLog.append(context, invalid))
        assertTrue(MemoryAuditLog.readVerified(context)!!.isEmpty())
    }
    @Test fun intentReservesCapacityForFailureAndRecoveryWithoutTruncatingHistory() {
        seedCommittedJournal(4_994)

        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "AUTHORIZED")))
        assertEquals(4_995, MemoryAuditLog.readVerified(context)!!.size)

        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.FAILED, "MUTATION_FAILED")))
        assertEquals(4_996, MemoryAuditLog.readVerified(context)!!.size)
        val pending = MemoryAuditLog.unresolvedOperations(context, "rule-1")!!.single()
        assertTrue(MemoryAuditLog.resolveUnresolved(context, pending, "test repair verified"))

        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(4_997, rows.size)
        assertEquals(MemoryAuditLog.Phase.COMMITTED, rows.last().phase)
        assertEquals("RECOVERED_STATE", rows.last().outcome)
        assertTrue(MemoryAuditLog.unresolvedOperations(context, "rule-1")!!.isEmpty())
        assertEquals("seed-4994", rows[4_993].objectId)
    }

    @Test fun failedUserMutationCanCompleteFullProposalRecoveryAtJournalCapacityBoundary() {
        seedCommittedJournal(4_994)
        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "AUTHORIZED")))
        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.FAILED, "MUTATION_FAILED")))
        val recoveryIntent = MemoryAuditLog.Event(
            actor = "SYSTEM_RECOVERY", mutation = "RECONCILE_APPROVED_PROPOSAL",
            objectId = "rule-1", reason = "recovery intent", approvalRef = "proposal:rule-1",
            phase = MemoryAuditLog.Phase.INTENT, outcome = "AUTHORIZED", timestamp = 1_800_000_000_100L
        )
        val recoveryTerminal = recoveryIntent.copy(
            phase = MemoryAuditLog.Phase.COMMITTED, outcome = "SUCCESS",
            timestamp = 1_800_000_000_101L
        )
        assertTrue(MemoryAuditLog.append(context, recoveryIntent))
        assertTrue(MemoryAuditLog.append(context, recoveryTerminal))
        val pendingUser = MemoryAuditLog.unresolvedOperations(context, "rule-1")!!
            .single { it.actor == "USER" }
        assertTrue(MemoryAuditLog.resolveUnresolved(context, pendingUser, "proposal stores verified"))

        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(4_999, rows.size)
        assertEquals("seed-4994", rows[4_993].objectId)
        assertEquals("RECOVERED_STATE", rows.last().outcome)
        assertTrue(MemoryAuditLog.unresolvedOperations(context, "rule-1")!!.isEmpty())
    }

    @Test fun failedRecoveryRetainsSlotsForBothVerifiedClosureRecords() {
        seedCommittedJournal(4_994)
        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "AUTHORIZED")))
        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.FAILED, "MUTATION_FAILED")))
        val recoveryIntent = MemoryAuditLog.Event(
            actor = "SYSTEM_RECOVERY", mutation = "RECONCILE_APPROVED_PROPOSAL",
            objectId = "rule-1", reason = "recovery intent", approvalRef = "proposal:rule-1",
            phase = MemoryAuditLog.Phase.INTENT, outcome = "AUTHORIZED", timestamp = 1_800_000_000_100L
        )
        assertTrue(MemoryAuditLog.append(context, recoveryIntent))
        assertTrue(MemoryAuditLog.append(context, recoveryIntent.copy(
            phase = MemoryAuditLog.Phase.FAILED, outcome = "MUTATION_FAILED",
            timestamp = 1_800_000_000_101L
        )))
        val pendingRecovery = MemoryAuditLog.unresolvedOperations(context, "rule-1")!!
            .single { it.actor == "SYSTEM_RECOVERY" }
        assertTrue(MemoryAuditLog.resolveUnresolved(context, pendingRecovery, "stores verified after repair"))
        val pendingUser = MemoryAuditLog.unresolvedOperations(context, "rule-1")!!
            .single { it.actor == "USER" }
        assertTrue(MemoryAuditLog.resolveUnresolved(context, pendingUser, "proposal stores verified"))

        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(5_000, rows.size)
        assertEquals("RECOVERED_STATE", rows[4_999].outcome)
        assertTrue(MemoryAuditLog.unresolvedOperations(context, "rule-1")!!.isEmpty())
    }

    @Test fun interruptedUserIntentCanStartRecoveryWithoutDoubleReservingCapacity() {
        seedCommittedJournal(4_994)
        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "AUTHORIZED")))
        val recoveryIntent = MemoryAuditLog.Event(
            actor = "SYSTEM_RECOVERY", mutation = "RECONCILE_APPROVED_PROPOSAL",
            objectId = "rule-1", reason = "recovery intent", approvalRef = "proposal:rule-1",
            phase = MemoryAuditLog.Phase.INTENT, outcome = "AUTHORIZED", timestamp = 1_800_000_000_100L
        )
        assertTrue(MemoryAuditLog.append(context, recoveryIntent))
        assertTrue(MemoryAuditLog.append(context, recoveryIntent.copy(
            phase = MemoryAuditLog.Phase.COMMITTED, outcome = "SUCCESS",
            timestamp = 1_800_000_000_101L
        )))
        val pendingUser = MemoryAuditLog.unresolvedOperations(context, "rule-1")!!
            .single { it.actor == "USER" }
        assertTrue(MemoryAuditLog.resolveUnresolved(context, pendingUser, "proposal stores verified"))
        assertEquals(4_998, MemoryAuditLog.readVerified(context)!!.size)
    }

    @Test fun intentIsRejectedWhenThreeJournalSlotsCannotBeGuaranteed() {
        for (count in listOf(4_998, 4_999, 5_000)) {
            context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE)
                .edit().clear().commit()
            SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
            seedCommittedJournal(count)

            assertFalse("INTENT must be refused at journal size $count",
                MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "AUTHORIZED")))
            assertEquals(count, MemoryAuditLog.readVerified(context)!!.size)
        }
    }


    @Test fun oversizedRawJournalAndOversizedFieldsAreRejectedWithoutMutation() {
        val prefs = context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE)
        val oversizedRaw = " ".repeat(4_000_001)
        assertTrue(prefs.edit().putString("events", oversizedRaw).commit())
        assertNull(MemoryAuditLog.readVerified(context))
        assertEquals(oversizedRaw, prefs.getString("events", null))

        val actor = "A".repeat(81)
        val mutation = "SEED"
        val objectId = "seed-1"
        val reason = "seed record"
        val approvalRef = ""
        val phase = MemoryAuditLog.Phase.COMMITTED.name
        val outcome = "SEED"
        val timestamp = 1_800_000_000_001L
        val previousHash = "GENESIS"
        val values = listOf("1", actor, mutation, objectId, reason, approvalRef,
            phase, outcome, timestamp.toString(), previousHash)
        val canonical = values.joinToString("") { "${it.length}:$it" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val oversizedFieldJournal = JSONArray().put(JSONObject()
            .put("sequence", 1L).put("actor", actor).put("mutation", mutation)
            .put("objectId", objectId).put("reason", reason).put("approvalRef", approvalRef)
            .put("phase", phase).put("outcome", outcome).put("timestamp", timestamp)
            .put("previousHash", previousHash).put("hash", hash)).toString()
        assertTrue(prefs.edit().putString("events", oversizedFieldJournal).commit())
        assertNull(MemoryAuditLog.readVerified(context))
        assertEquals(oversizedFieldJournal, prefs.getString("events", null))
        assertFalse(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "AUTHORIZED")))
    }


}
