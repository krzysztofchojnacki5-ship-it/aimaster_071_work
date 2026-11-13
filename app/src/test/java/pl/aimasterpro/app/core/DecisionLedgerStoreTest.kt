package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DecisionLedgerStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("aiforteca_decision_ledger_v1", Context.MODE_PRIVATE)

    @Before fun reset() {
        SharedPreferencesWriteHealth.resetForTests("aiforteca_decision_ledger_v1")
        prefs.edit().clear().commit()
    }

    private fun entry(
        id: String,
        outcome: String = "PROPOSED",
        supersedes: String = ""
    ) = DecisionLedgerStore.Entry(
        decisionId = id,
        taskId = "task-$id",
        timestampUtc = 1_700_000_000_000L,
        actor = "MASTER",
        context = "Projekt AiFORTeca",
        objective = "Dodać trwały rejestr decyzji",
        optionsConsidered = listOf("SharedPreferences", "Room"),
        selectedOption = "SharedPreferences",
        rationale = "Pierwszy etap bez migracji istniejących danych.",
        evidenceRefs = listOf("commit:abcdef1234567"),
        assumptions = "Mały wolumen początkowy",
        confidence = "MEDIUM",
        riskImpact = "Ograniczony",
        approvalRequired = false,
        approvalReference = "",
        outcome = outcome,
        reviewTrigger = "Po wdrożeniu Room",
        supersedesDecisionId = supersedes
    )

    @Test fun appendsAndReloadsDecisionWithoutMutatingEarlierEntry() {
        assertTrue(DecisionLedgerStore.append(context, entry("d-1")))
        assertTrue(DecisionLedgerStore.append(context, entry("d-2", supersedes = "d-1")))

        val snapshot = DecisionLedgerStore.snapshot(context)
        assertEquals(DecisionLedgerStore.ReadStatus.AVAILABLE, snapshot.status)
        assertEquals(listOf("d-2", "d-1"), snapshot.entries.map { it.decisionId })
        assertEquals("", DecisionLedgerStore.get(context, "d-1")?.supersedesDecisionId)
        assertEquals("d-1", DecisionLedgerStore.get(context, "d-2")?.supersedesDecisionId)
    }

    @Test fun rejectsDuplicateIdsAndUnknownSupersededDecision() {
        assertTrue(DecisionLedgerStore.append(context, entry("d-1")))
        assertFalse(DecisionLedgerStore.append(context, entry("d-1")))
        assertFalse(DecisionLedgerStore.append(context, entry("d-2", supersedes = "missing")))
        assertEquals(listOf("d-1"), DecisionLedgerStore.snapshot(context).entries.map { it.decisionId })
    }

    @Test fun corruptLedgerIsReportedAndNeverOverwrittenAsEmpty() {
        val raw = "{not-json"
        assertTrue(prefs.edit().putString("entries", raw).commit())

        val snapshot = DecisionLedgerStore.snapshot(context)
        assertEquals(DecisionLedgerStore.ReadStatus.CORRUPT, snapshot.status)
        assertFalse(DecisionLedgerStore.append(context, entry("d-1")))
        assertEquals(raw, prefs.getString("entries", null))
    }

    @Test fun contextStringReturnsRecentDecisionMetadataWithoutPromptContent() {
        assertTrue(DecisionLedgerStore.append(context, entry("d-1")))
        val rendered = DecisionLedgerStore.contextString(context)
        assertTrue(rendered.contains("Dodać trwały rejestr decyzji"))
        assertTrue(rendered.contains("SharedPreferences"))
        assertFalse(rendered.contains("raw prompt"))
    }

    @Test fun contextStringReportsUnavailableLedgerInsteadOfPretendingItIsEmpty() {
        SharedPreferencesWriteHealth.commit("aiforteca_decision_ledger_v1", object : android.content.SharedPreferences.Editor by prefs.edit() {
            override fun commit(): Boolean = false
        })
        val rendered = DecisionLedgerStore.contextString(context)
        assertTrue(rendered.contains("UNAVAILABLE"))
    }

    @Test fun rejectsInvalidDecisionBeforeWriting() {
        val invalid = entry("d-1").copy(objective = "")
        assertFalse(DecisionLedgerStore.append(context, invalid))
        assertEquals(DecisionLedgerStore.ReadStatus.AVAILABLE, DecisionLedgerStore.snapshot(context).status)
        assertTrue(DecisionLedgerStore.snapshot(context).entries.isEmpty())
    }

    @Test fun quarantinedStorageIsNotReportedAsEmptyHealthyLedger() {
        SharedPreferencesWriteHealth.commit("aiforteca_decision_ledger_v1", object : android.content.SharedPreferences.Editor by prefs.edit() {
            override fun commit(): Boolean = false
        })

        val snapshot = DecisionLedgerStore.snapshot(context)
        assertEquals(DecisionLedgerStore.ReadStatus.UNAVAILABLE, snapshot.status)
        assertFalse(DecisionLedgerStore.append(context, entry("d-1")))
    }
    @Test fun oversizedRawDecisionLedgerIsRejectedBeforeParsingAndPreserved() {
        val oversized = " ".repeat(4_000_001)
        assertTrue(prefs.edit().putString("entries", oversized).commit())

        val snapshot = DecisionLedgerStore.snapshot(context)
        assertEquals(DecisionLedgerStore.ReadStatus.CORRUPT, snapshot.status)
        assertFalse(DecisionLedgerStore.append(context, entry("new-decision")))
        assertEquals(oversized, prefs.getString("entries", null))
    }
}
