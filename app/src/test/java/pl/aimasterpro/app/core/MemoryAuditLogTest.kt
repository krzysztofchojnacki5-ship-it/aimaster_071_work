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
        mutation = "ADD_SHARED_RULE",
        objectId = "rule-1",
        reason = "Jawne zatwierdzenie w interfejsie",
        approvalRef = "ui-confirmation:rule-1",
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
        seedCommittedJournal(4_997)

        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.INTENT, "AUTHORIZED")))
        assertEquals(4_998, MemoryAuditLog.readVerified(context)!!.size)

        assertTrue(MemoryAuditLog.append(context, event(MemoryAuditLog.Phase.FAILED, "MUTATION_FAILED")))
        assertEquals(4_999, MemoryAuditLog.readVerified(context)!!.size)
        val pending = MemoryAuditLog.unresolvedOperations(context, "rule-1")!!.single()
        assertTrue(MemoryAuditLog.resolveUnresolved(context, pending, "test repair verified"))

        val rows = MemoryAuditLog.readVerified(context)!!
        assertEquals(5_000, rows.size)
        assertEquals(MemoryAuditLog.Phase.COMMITTED, rows.last().phase)
        assertEquals("RECOVERED_STATE", rows.last().outcome)
        assertTrue(MemoryAuditLog.unresolvedOperations(context, "rule-1")!!.isEmpty())
        assertEquals("seed-4997", rows[4_996].objectId)
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

}
