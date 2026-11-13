package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentEfficiencyTrackerPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("agent_efficiency_history_v1", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    private fun validRow() = """{"ts":10,"task":"task-1","agent":"agent-a","status":"COMPLETED","duration":100,"attempts":1,"provider":"p","model":"m","error":""}"""

    @Test fun malformedJsonReturnsNoPartialHistoryAndIsNeverOverwritten() {
        val raw = """[${validRow()},null]"""
        prefs.edit().putString("outcomes", raw).commit()
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            11L, "task-2", "agent-a", "COMPLETED", 20L, 1, "p", "m", ""
        ))
        assertEquals(raw, prefs.getString("outcomes", null))
    }

    @Test fun fractionalAndNegativeCountersInvalidateWholeHistory() {
        val fractional = validRow().replace("\"attempts\":1", "\"attempts\":1.5")
        prefs.edit().putString("outcomes", """[$fractional]""").commit()
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
        val negative = validRow().replace("\"duration\":100", "\"duration\":-1")
        prefs.edit().putString("outcomes", """[$negative]""").commit()
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
    }

    @Test fun missingIdentityAndWrongFieldTypesInvalidateWholeHistory() {
        val missing = validRow().replace("\"agent\":\"agent-a\",", "")
        prefs.edit().putString("outcomes", """[$missing]""").commit()
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
        val wrongType = validRow().replace("\"status\":\"COMPLETED\"", "\"status\":7")
        prefs.edit().putString("outcomes", """[$wrongType]""").commit()
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
    }

    @Test fun statsAverageDoesNotOverflowForLargeValidDurations() {
        val max = Long.MAX_VALUE
        val first = validRow().replace("\"duration\":100", "\"duration\":" + max)
        val second = validRow().replace("\"task\":\"task-1\"", "\"task\":\"task-2\"")
            .replace("\"duration\":100", "\"duration\":" + (max - 2))
        prefs.edit().putString("outcomes", """[$first,$second]""").commit()
        val stats = AgentEfficiencyTracker.stats(context).single()
        assertEquals(max - 1, stats.avgDurationMs)
    }

    @Test fun statsAttemptAverageDoesNotOverflowIntAccumulation() {
        val rows = (0 until 500).map { index ->
            validRow().replace("\"task\":\"task-1\"", "\"task\":\"task-" + index + "\"")
                .replace("\"attempts\":1", "\"attempts\":" + Int.MAX_VALUE)
        }
        prefs.edit().putString("outcomes", """[${rows.joinToString(",")}]""").commit()
        val stats = AgentEfficiencyTracker.stats(context).single()
        assertEquals(Int.MAX_VALUE.toDouble(), stats.avgAttempts, 0.0)
    }
    @Test fun validHistoryRoundTripsAndCanBeExtended() {
        prefs.edit().putString("outcomes", """[${validRow()}]""").commit()
        assertEquals(1, AgentEfficiencyTracker.load(context).size)
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            11L, "task-2", "agent-a", "FAILED", 50L, 2, "p", "m", "network"
        ))
        val rows = AgentEfficiencyTracker.load(context)
        assertEquals(2, rows.size)
        assertEquals("task-2", rows.first().taskId)
    }
    @Test fun oversizedRawEfficiencyHistoryIsRejectedAndPreserved() {
        val oversized = " ".repeat(1_000_001)
        prefs.edit().putString("outcomes", oversized).commit()
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            12L, "task-3", "agent-a", "COMPLETED", 20L, 1, "p", "m", ""
        ))
        assertEquals(oversized, prefs.getString("outcomes", null))
    }


    @Test fun recordRejectsOversizedIdentityInsteadOfSilentlyTruncatingIt() {
        val original = "t".repeat(161)
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            12L, original, "agent-a", "COMPLETED", 10L, 1, "p", "m", ""
        ))
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
        assertNull(prefs.getString("outcomes", null))
    }

    @Test fun recordRedactsSecretsInProviderModelAndErrorMetadata() {
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            12L, "task-safe", "agent-a", "FAILED", 10L, 1,
            "api_key=sk_live_1234567890", "model-token=ghp_12345678901234567890",
            "Bearer ghp_12345678901234567890"
        ))
        val row = AgentEfficiencyTracker.load(context).single()
        assertFalse(row.providerId.contains("sk_live_"))
        assertFalse(row.model.contains("ghp_"))
        assertFalse(row.errorCategory.contains("ghp_"))
        assertTrue(prefs.getString("outcomes", "").orEmpty().contains("[REDACTED]"))
    }

    @Test fun zeroTimestampIsRejectedOnWriteAndInPersistedHistory() {
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            0L, "task-zero", "agent-a", "COMPLETED", 10L, 1, "p", "m", ""
        ))
        assertNull(prefs.getString("outcomes", null))
        val zero = validRow().replace("\"ts\":10", "\"ts\":0")
        prefs.edit().putString("outcomes", """[$zero]""").commit()
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
    }

    @Test fun persistedSecretMetadataInvalidatesWholeHistoryWithoutRepairingIt() {
        val unsafe = validRow().replace("\"provider\":\"p\"", "\"provider\":\"api_key=sk_live_1234567890\"")
        prefs.edit().putString("outcomes", """[$unsafe]""").commit()
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            12L, "task-new", "agent-a", "COMPLETED", 10L, 1, "p", "m", ""
        ))
        assertTrue(prefs.getString("outcomes", null) == """[$unsafe]""")
    }

    @Test fun failedDurableCommitQuarantinesEfficiencyHistory() {
        SharedPreferencesWriteHealth.resetForTests("agent_efficiency_history_v1")
        val editor = org.mockito.Mockito.mock(android.content.SharedPreferences.Editor::class.java)
        org.mockito.Mockito.`when`(editor.commit()).thenReturn(false)
        assertFalse(SharedPreferencesWriteHealth.commit("agent_efficiency_history_v1", editor))
        AgentEfficiencyTracker.record(context, AgentEfficiencyTracker.Outcome(
            12L, "task-after-failure", "agent-a", "COMPLETED", 10L, 1, "p", "m", ""
        ))
        assertTrue(AgentEfficiencyTracker.load(context).isEmpty())
        assertTrue(SharedPreferencesWriteHealth.isUncertain("agent_efficiency_history_v1"))
        SharedPreferencesWriteHealth.resetForTests("agent_efficiency_history_v1")
    }
}
