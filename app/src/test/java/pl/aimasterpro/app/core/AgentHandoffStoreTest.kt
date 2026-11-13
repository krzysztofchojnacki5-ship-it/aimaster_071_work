package pl.aimasterpro.app.core

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentHandoffStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("aiforteca_master_task_queue_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("aiforteca_agent_manager_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("aiforteca_agent_handoff_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("aiforteca_master_task_queue_v1")
        SharedPreferencesWriteHealth.resetForTests("aiforteca_agent_handoff_v1")
        AgentManagerStore.resetStorageHealthForTests()
    }

    private fun runningTask(): Int {
        assertTrue(MasterTaskQueueStore.enqueue(context, "handoff-task-01", "Współpraca", "Przekaż ustalenia.", now = 10L))
        assertTrue(MasterTaskQueueStore.assign(context, "handoff-task-01",
            listOf("critical-reviewer", "unit-test-engineer"), now = 11L))
        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Start przebiegu.", 12L))
        return MasterTaskQueueStore.snapshot(context).tasks.single().runId
    }

    @Test fun sendIsIdempotentAndPersistsOnlyOnePendingMessage() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-001",
            "critical-reviewer", "unit-test-engineer", "Sprawdź ryzyko regresji.", 20L))
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-001",
            "critical-reviewer", "unit-test-engineer", "Sprawdź ryzyko regresji.", 21L))
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-001",
            "critical-reviewer", "unit-test-engineer", "Inna treść pod tym samym ID.", 22L))
        val snapshot = AgentHandoffStore.snapshot(context)
        assertEquals(AgentHandoffStore.ReadStatus.AVAILABLE, snapshot.status)
        assertEquals(1, snapshot.messages.size)
        assertEquals(AgentHandoffStore.State.PENDING, snapshot.messages.single().state)
    }

    @Test fun sanitizedPayloadRetryRemainsIdempotent() {
        val runId = runningTask()
        val raw = "Raport: api_key=sk_live_1234567890"
        assertEquals("Raport: api_key=[REDACTED]", CommunicationLogSanitizer.sanitize(raw))
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-006",
            "critical-reviewer", "unit-test-engineer", raw, 23L))
        assertTrue("Retry with the same raw payload must compare against its sanitized form",
            AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-006",
                "critical-reviewer", "unit-test-engineer", raw, 24L))
        val snapshot = AgentHandoffStore.snapshot(context)
        assertEquals(1, snapshot.messages.size)
        assertEquals("Raport: api_key=[REDACTED]", snapshot.messages.single().payload)
    }

    @Test fun onlyAssignedSenderAndAddressedRecipientCanClaimAndAcknowledge() {
        val runId = runningTask()
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-002",
            "debugger", "unit-test-engineer", "Niedozwolony nadawca.", 20L))
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-002",
            "critical-reviewer", "unit-test-engineer", "Sprawdź testy.", 21L))
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-002",
            "critical-reviewer", 22L))
        val claimed = AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-002",
            "unit-test-engineer", 23L)
        assertNotNull(claimed)
        assertEquals(AgentHandoffStore.State.CLAIMED, claimed?.state)
        assertFalse(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-msg-002",
            "critical-reviewer", 24L))
        assertTrue(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-msg-002",
            "unit-test-engineer", 25L))
        assertTrue(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-msg-002",
            "unit-test-engineer", 26L))
        assertEquals(AgentHandoffStore.State.ACKNOWLEDGED,
            AgentHandoffStore.snapshot(context).messages.single().state)
    }

    @Test fun addressedRecipientCanRejectClaimWithSanitizedIdempotentAuditReason() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-reject-001",
            "critical-reviewer", "unit-test-engineer", "Sprawdź ryzyko regresji.", 90L))

        assertFalse(AgentHandoffStore.reject(context, "handoff-task-01", runId, "handoff-reject-001",
            "critical-reviewer", "Nie jestem odbiorcą.", 91L))
        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-reject-001",
            "unit-test-engineer", 92L))
        assertFalse("A rejection timestamp before the claim must not close the handoff",
            AgentHandoffStore.reject(context, "handoff-task-01", runId, "handoff-reject-001",
                "unit-test-engineer", "Za wczesna decyzja.", 91L))
        assertEquals(AgentHandoffStore.State.CLAIMED,
            AgentHandoffStore.snapshot(context).messages.single().state)

        val rawReason = "Odrzucono po analizie: api_key=sk_live_1234567890"
        assertTrue(AgentHandoffStore.reject(context, "handoff-task-01", runId, "handoff-reject-001",
            "unit-test-engineer", rawReason, 93L))
        assertTrue("A retry with the same sanitized reason must be idempotent",
            AgentHandoffStore.reject(context, "handoff-task-01", runId, "handoff-reject-001",
                "unit-test-engineer", rawReason, 94L))

        val rejected = AgentHandoffStore.snapshot(context).messages.single()
        assertEquals(AgentHandoffStore.State.REJECTED, rejected.state)
        assertEquals(93L, rejected.updatedAt)
        assertEquals("Odrzucono po analizie: api_key=[REDACTED]", rejected.resolutionReason)
        assertEquals("", rejected.resolutionActor)
        assertFalse(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId,
            "handoff-reject-001", "unit-test-engineer", 95L))
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId,
            "handoff-reject-001", "unit-test-engineer", 95L))
        assertFalse(AgentHandoffStore.reject(context, "handoff-task-01", runId, "handoff-reject-001",
            "unit-test-engineer", "Inny powód.", 96L))
    }

    @Test fun claimAndAcknowledgementRejectTimestampsThatMoveBackwards() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-time-001",
            "critical-reviewer", "unit-test-engineer", "Raport z kontrolą czasu.", 100L))

        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-time-001",
            "unit-test-engineer", 99L))
        assertEquals(AgentHandoffStore.State.PENDING,
            AgentHandoffStore.snapshot(context).messages.single().state)

        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-time-001",
            "unit-test-engineer", 101L))
        assertFalse(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-time-001",
            "unit-test-engineer", 100L))
        assertEquals(AgentHandoffStore.State.CLAIMED,
            AgentHandoffStore.snapshot(context).messages.single().state)

        assertTrue(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-time-001",
            "unit-test-engineer", 102L))
        assertEquals(AgentHandoffStore.State.ACKNOWLEDGED,
            AgentHandoffStore.snapshot(context).messages.single().state)
    }

    @Test fun zeroTimestampCannotCreateAnUnclaimableHandoff() {
        val runId = runningTask()
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-zero-001",
            "critical-reviewer", "unit-test-engineer", "Niepoprawny czas utworzenia.", 0L))
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-zero-002",
            "critical-reviewer", "unit-test-engineer", "Poprawny czas utworzenia.", 100L))
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-zero-002",
            "unit-test-engineer", 0L))
        assertEquals(AgentHandoffStore.State.PENDING, AgentHandoffStore.snapshot(context).messages.single().state)
        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-zero-002",
            "unit-test-engineer", 101L))
    }

    @Test fun claimedMessageIsNotReplayedAfterStoreIsReadAgain() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-003",
            "critical-reviewer", "unit-test-engineer", "Wykonaj kontrolę.", 30L))
        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-003",
            "unit-test-engineer", 31L))
        // A fresh snapshot simulates process recreation; uncertain work is never auto-replayed.
        assertEquals(AgentHandoffStore.State.CLAIMED,
            AgentHandoffStore.snapshot(context).messages.single().state)
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-003",
            "unit-test-engineer", 32L))
    }


    @Test fun cancelledParentRejectsNewAndPendingHandoffCallbacks() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-cancel-01",
            "critical-reviewer", "unit-test-engineer", "Raport przed anulowaniem.", 50L))
        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "USER",
            MasterTaskQueueStore.Status.CANCELLED, "Użytkownik przerwał zadanie.", 51L))

        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-cancel-02",
            "critical-reviewer", "unit-test-engineer", "Nowe przekazanie po anulowaniu.", 52L))
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-cancel-01",
            "unit-test-engineer", 53L))
        assertFalse(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, "handoff-msg-cancel-01",
            "unit-test-engineer", 54L))
        val snapshot = AgentHandoffStore.snapshot(context)
        assertEquals(1, snapshot.messages.size)
        assertEquals(AgentHandoffStore.State.PENDING, snapshot.messages.single().state)
    }

    @Test fun acknowledgedHandoffsRemainInAuditTrailAndCapacityRejectsNewWork() {
        val runId = runningTask()
        val firstId = "handoff-msg-000"
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, firstId,
            "critical-reviewer", "unit-test-engineer", "Raport zakończony.", 100L))
        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, firstId,
            "unit-test-engineer", 101L))
        assertTrue(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId, firstId,
            "unit-test-engineer", 102L))

        for (index in 1 until 400) {
            val id = "handoff-msg-${index.toString().padStart(3, '0')}"
            assertTrue("Could not add $id", AgentHandoffStore.send(
                context, "handoff-task-01", runId, id, "critical-reviewer",
                "unit-test-engineer", "Raport oczekujący $index.", 102L + index
            ))
        }
        assertEquals(400, AgentHandoffStore.snapshot(context).messages.size)

        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-400",
            "critical-reviewer", "unit-test-engineer", "Nowe przekazanie.", 600L))
        val snapshot = AgentHandoffStore.snapshot(context)
        assertEquals(400, snapshot.messages.size)
        assertTrue(snapshot.messages.any { it.id == firstId && it.state == AgentHandoffStore.State.ACKNOWLEDGED })
        assertTrue(snapshot.messages.any { it.id == "handoff-msg-001" })
        assertFalse(snapshot.messages.any { it.id == "handoff-msg-400" })
        assertEquals(399, snapshot.messages.count { it.state == AgentHandoffStore.State.PENDING })
    }

    @Test fun inboxNeverEvictsPendingOrClaimedHandoffsToMakeRoom() {
        val runId = runningTask()
        for (index in 0 until 400) {
            val id = "handoff-full-${index.toString().padStart(3, '0')}"
            assertTrue("Could not add $id", AgentHandoffStore.send(
                context, "handoff-task-01", runId, id, "critical-reviewer",
                "unit-test-engineer", "Nierozstrzygnięte przekazanie $index.", 700L + index
            ))
        }
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-full-400",
            "critical-reviewer", "unit-test-engineer", "Nie wolno usunąć oczekujących.", 1_200L))
        val snapshot = AgentHandoffStore.snapshot(context)
        assertEquals(400, snapshot.messages.size)
        assertTrue(snapshot.messages.all { it.state == AgentHandoffStore.State.PENDING })
        assertFalse(snapshot.messages.any { it.id == "handoff-full-400" })
    }

    @Test fun interruptedClaimCanOnlyBeManuallyResolvedAsUnknownWhileExactRunIsBlocked() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-manual-01",
            "critical-reviewer", "unit-test-engineer", "Raport o niepewnym wyniku.", 60L))
        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-manual-01",
            "unit-test-engineer", 61L))

        assertFalse(AgentHandoffStore.resolveUncertainClaim(context, "handoff-task-01", runId,
            "handoff-msg-manual-01", "USER", "Nie wolno rozstrzygać aktywnego przebiegu.", 62L))
        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "USER",
            MasterTaskQueueStore.Status.BLOCKED, "Przerwano przebieg.", 63L))
        assertFalse(AgentHandoffStore.resolveUncertainClaim(context, "handoff-task-01", runId,
            "handoff-msg-manual-01", "agent:unit-test-engineer", "Nieuprawniony aktor.", 64L))
        assertFalse(AgentHandoffStore.resolveUncertainClaim(context, "handoff-task-01", runId,
            "handoff-msg-manual-01", "USER", " ", 64L))
        assertTrue(AgentHandoffStore.resolveUncertainClaim(context, "handoff-task-01", runId,
            "handoff-msg-manual-01", "USER", "Wynik zewnętrzny pozostaje nieznany.", 65L))

        val resolved = AgentHandoffStore.snapshot(context).messages.single()
        assertEquals(AgentHandoffStore.State.MANUALLY_RESOLVED, resolved.state)
        assertEquals(65L, resolved.resolvedAt)
        assertEquals("USER", resolved.resolutionActor)
        assertEquals("Wynik zewnętrzny pozostaje nieznany.", resolved.resolutionReason)
        assertFalse(AgentHandoffStore.acknowledge(context, "handoff-task-01", runId,
            "handoff-msg-manual-01", "unit-test-engineer", 66L))
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId,
            "handoff-msg-manual-01", "unit-test-engineer", 66L))
    }

    @Test fun manualResolutionRejectsSanitizerExpansionInsteadOfTruncatingAuditReason() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-reason-001",
            "critical-reviewer", "unit-test-engineer", "Raport do ręcznej decyzji.", 200L))
        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-reason-001",
            "unit-test-engineer", 201L))
        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "USER",
            MasterTaskQueueStore.Status.BLOCKED, "Przerwano przebieg.", 202L))
        val reason = "x".repeat(289) + " password=x"
        assertEquals(300, reason.length)
        assertTrue(CommunicationLogSanitizer.sanitize(reason).length > 300)

        assertFalse(AgentHandoffStore.resolveUncertainClaim(context, "handoff-task-01", runId,
            "handoff-reason-001", "USER", reason, 203L))
        val unchanged = AgentHandoffStore.snapshot(context).messages.single()
        assertEquals(AgentHandoffStore.State.CLAIMED, unchanged.state)
        assertEquals("", unchanged.resolutionReason)
        assertEquals(0L, unchanged.resolvedAt)
    }

    @Test fun manualResolutionCannotClosePendingOrPreviousRunHandoff() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-manual-02",
            "critical-reviewer", "unit-test-engineer", "Raport oczekujący.", 70L))
        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "USER",
            MasterTaskQueueStore.Status.BLOCKED, "Przerwano przed przejęciem.", 71L))
        assertFalse(AgentHandoffStore.resolveUncertainClaim(context, "handoff-task-01", runId,
            "handoff-msg-manual-02", "USER", "Nie wolno zamknąć PENDING.", 72L))

        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "USER",
            MasterTaskQueueStore.Status.QUEUED, "Ponowienie po ręcznej decyzji.", 73L))
        assertTrue(MasterTaskQueueStore.assign(context, "handoff-task-01",
            listOf("critical-reviewer", "unit-test-engineer"), 74L))
        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "MASTER",
            MasterTaskQueueStore.Status.RUNNING, "Nowy przebieg.", 75L))
        val newRunId = MasterTaskQueueStore.snapshot(context).tasks.single().runId
        assertTrue(newRunId > runId)
        assertFalse(AgentHandoffStore.resolveUncertainClaim(context, "handoff-task-01", runId,
            "handoff-msg-manual-02", "USER", "Stary przebieg.", 76L))
        assertEquals(AgentHandoffStore.State.PENDING,
            AgentHandoffStore.snapshot(context).messages.single().state)
    }

    @Test fun manualResolutionFailsClosedWhenHandoffStorageIsCorrupt() {
        val runId = runningTask()
        assertTrue(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-manual-corrupt",
            "critical-reviewer", "unit-test-engineer", "Raport o niepewnym wyniku.", 80L))
        assertNotNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-msg-manual-corrupt",
            "unit-test-engineer", 81L))
        assertTrue(MasterTaskQueueStore.transition(context, "handoff-task-01", "USER",
            MasterTaskQueueStore.Status.BLOCKED, "Przerwano przebieg.", 82L))
        context.getSharedPreferences("aiforteca_agent_handoff_v1", Context.MODE_PRIVATE)
            .edit().putString("messages", "{broken").commit()

        assertFalse(AgentHandoffStore.resolveUncertainClaim(context, "handoff-task-01", runId,
            "handoff-msg-manual-corrupt", "USER", "Nie wolno zapisać decyzji przy uszkodzonej skrzynce.", 83L))
        assertEquals(AgentHandoffStore.ReadStatus.CORRUPT, AgentHandoffStore.snapshot(context).status)
    }

    @Test fun staleRunAndUnassignedRecipientsAreRejected() {
        val runId = runningTask()
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId - 1, "handoff-msg-004",
            "critical-reviewer", "unit-test-engineer", "Stary przebieg.", 40L))
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-005",
            "critical-reviewer", "debugger", "Odbiorca spoza zespołu.", 41L))
        assertTrue(AgentHandoffStore.snapshot(context).messages.isEmpty())
    }

    @Test fun persistedHandoffsRejectSelfAddressingAndUnredactedSecrets() {
        val runId = runningTask()
        val prefs = context.getSharedPreferences("aiforteca_agent_handoff_v1", Context.MODE_PRIVATE)

        fun writeRecord(sender: String, recipient: String, payload: String) {
            val record = org.json.JSONObject()
                .put("id", "handoff-corrupt-01")
                .put("taskId", "handoff-task-01")
                .put("runId", runId)
                .put("senderAgentId", sender)
                .put("recipientAgentId", recipient)
                .put("payload", payload)
                .put("state", "PENDING")
                .put("createdAt", 90L)
                .put("updatedAt", 90L)
                .put("claimedAt", 0L)
                .put("resolvedAt", 0L)
                .put("resolutionActor", "")
                .put("resolutionReason", "")
            prefs.edit().putString("messages", org.json.JSONArray().put(record).toString()).commit()
        }

        writeRecord("critical-reviewer", "critical-reviewer", "Niepoprawne przekazanie.")
        assertEquals(AgentHandoffStore.ReadStatus.CORRUPT, AgentHandoffStore.snapshot(context).status)

        writeRecord("critical-reviewer", "unit-test-engineer", "api_key=sk_live_1234567890")
        assertEquals(AgentHandoffStore.ReadStatus.CORRUPT, AgentHandoffStore.snapshot(context).status)
    }


    @Test fun handoffNumericFieldsRejectCoercionAndPreserveCorruptBytes() {
        val runId = runningTask()
        val prefs = context.getSharedPreferences("aiforteca_agent_handoff_v1", Context.MODE_PRIVATE)
        fun record() = org.json.JSONObject()
            .put("id", "handoff-strict-001").put("taskId", "handoff-task-01").put("runId", runId)
            .put("senderAgentId", "critical-reviewer").put("recipientAgentId", "unit-test-engineer")
            .put("payload", "Raport poprawny.").put("state", "PENDING")
            .put("createdAt", 90L).put("updatedAt", 90L).put("claimedAt", 0L).put("resolvedAt", 0L)
            .put("resolutionActor", "").put("resolutionReason", "")
        val malformed = listOf(
            record().put("runId", runId.toString()),
            record().put("createdAt", "90"),
            record().put("updatedAt", 90.5),
            record().put("claimedAt", "0"),
            record().put("resolutionActor", 7)
        )
        malformed.forEach { item ->
            val raw = org.json.JSONArray().put(item).toString()
            assertTrue(prefs.edit().putString("messages", raw).commit())
            assertEquals(AgentHandoffStore.ReadStatus.CORRUPT, AgentHandoffStore.snapshot(context).status)
            assertEquals(raw, prefs.getString("messages", null))
        }
    }

    @Test fun persistedHandoffStatesRejectImpossibleTimestampTransitionsAndPreserveBytes() {
        runningTask()
        val prefs = context.getSharedPreferences("aiforteca_agent_handoff_v1", Context.MODE_PRIVATE)
        fun record(state: String, updatedAt: Long, claimedAt: Long) = org.json.JSONObject()
            .put("id", "handoff-state-001").put("taskId", "handoff-task-01").put("runId", 1)
            .put("senderAgentId", "critical-reviewer").put("recipientAgentId", "unit-test-engineer")
            .put("payload", "Raport poprawny.").put("state", state)
            .put("createdAt", 100L).put("updatedAt", updatedAt).put("claimedAt", claimedAt)
            .put("resolvedAt", 0L).put("resolutionActor", "").put("resolutionReason", "")

        val malformed = listOf(
            record("PENDING", 101L, 0L),
            record("CLAIMED", 112L, 111L),
            record("ACKNOWLEDGED", 109L, 110L),
            record("REJECTED", 112L, 111L)
        )
        malformed.forEach { item ->
            val raw = org.json.JSONArray().put(item).toString()
            assertTrue(prefs.edit().putString("messages", raw).commit())
            assertEquals(AgentHandoffStore.ReadStatus.CORRUPT, AgentHandoffStore.snapshot(context).status)
            assertEquals("Malformed state history must remain untouched", raw, prefs.getString("messages", null))
        }
    }

    @Test fun ambiguousCommitQuarantinesHandoffInboxAndRejectsFurtherWrites() {
        val runId = runningTask()
        val prefs = context.getSharedPreferences("aiforteca_agent_handoff_v1", Context.MODE_PRIVATE)
        val failingEditor = object : SharedPreferences.Editor by prefs.edit().putString("messages", "[]") {
            override fun commit(): Boolean = false
        }

        assertFalse(SharedPreferencesWriteHealth.commit("aiforteca_agent_handoff_v1", failingEditor))
        assertEquals(AgentHandoffStore.ReadStatus.UNAVAILABLE, AgentHandoffStore.snapshot(context).status)
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-fault-001",
            "critical-reviewer", "unit-test-engineer", "Nie wolno potwierdzać niepewnego zapisu.", 90L))
        assertNull(AgentHandoffStore.claim(context, "handoff-task-01", runId, "handoff-fault-001",
            "unit-test-engineer", 91L))
    }
    @Test fun sanitizedPayloadThatExpandsPastLimitIsRejectedWithoutTruncation() {
        val runId = runningTask()
        val raw = "a".repeat(7_989) + " password=x"
        assertEquals(8_000, raw.length)
        assertTrue(CommunicationLogSanitizer.sanitize(raw).length > 8_000)

        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", runId, "handoff-msg-overflow",
            "critical-reviewer", "unit-test-engineer", raw, 25L))
        assertTrue(AgentHandoffStore.snapshot(context).messages.isEmpty())
    }

    @Test fun oversizedRawInboxIsRejectedBeforeJsonParsingAndPreserved() {
        val prefs = context.getSharedPreferences("aiforteca_agent_handoff_v1", Context.MODE_PRIVATE)
        // A valid JSON document with excessive trailing whitespace must be rejected by the
        // raw-size guard before the parser accepts it as an empty inbox.
        val oversized = "[]" + " ".repeat(4_000_001 - 2)
        assertTrue(prefs.edit().putString("messages", oversized).commit())
        assertEquals(AgentHandoffStore.ReadStatus.CORRUPT, AgentHandoffStore.snapshot(context).status)
        assertFalse(AgentHandoffStore.send(context, "handoff-task-01", 1, "handoff-new-001",
            "critical-reviewer", "unit-test-engineer", "Nie nadpisuj uszkodzonej skrzynki.", 100L))
        assertEquals(oversized, prefs.getString("messages", null))
    }

}
