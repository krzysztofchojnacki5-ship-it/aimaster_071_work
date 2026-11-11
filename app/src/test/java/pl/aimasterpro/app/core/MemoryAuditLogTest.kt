package pl.aimasterpro.app.core

import android.content.Context
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
}
