package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentOperationalMemoryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun memoryIsScopedToTheOwningAgent() {
        AgentOperationalMemoryStore.addApproved(context, "code-analyst", "W Kotlin sprawdzaj null safety oraz testy jednostkowe.")
        assertTrue(AgentOperationalMemoryStore.contextString(context, "code-analyst", "Kotlin null safety", 900).contains("null safety"))
        assertEquals("", AgentOperationalMemoryStore.contextString(context, "security-agent", "Kotlin null safety", 900))
    }

    @Test fun retrievalIsRelevantAndBounded() {
        AgentOperationalMemoryStore.addApproved(context, "tester", "Dla testów Gradle uruchamiaj testDebugUnitTest przed assembleDebug.")
        AgentOperationalMemoryStore.addApproved(context, "tester", "Przy grafice sprawdzaj proporcje obrazu.")
        val result = AgentOperationalMemoryStore.contextString(context, "tester", "Gradle testDebugUnitTest", maxChars = 300, maxItems = 4)
        assertTrue(result.length <= 300)
        assertTrue(result.contains("testDebugUnitTest"))
        assertFalse(result.contains("proporcje obrazu"))
    }

    @Test fun duplicateEntriesMergeTheirProvenanceAndCanBeRemoved() {
        val first = AgentOperationalMemoryStore.addApproved(context, "architect", "Zachowuj kompatybilność API przy refaktoryzacji.", sourceTaskIds = listOf("task-a"))!!
        val second = AgentOperationalMemoryStore.addApproved(context, "architect", "Zachowuj kompatybilność API przy refaktoryzacji.", sourceTaskIds = listOf("task-b"))!!
        assertEquals(first.id, second.id)
        assertEquals(setOf("task-a", "task-b"), second.sourceTaskIds.toSet())
        AgentOperationalMemoryStore.removeBySource(context, "task-a")
        assertEquals(listOf("task-b"), AgentOperationalMemoryStore.all(context).single().sourceTaskIds)
        assertTrue(AgentOperationalMemoryStore.remove(context, first.id))
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
    }

    @Test fun malformedOperationalMemoryIsNotOverwrittenByMutation() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"id":"entry-1","agentId":"tester","text":"valid row","createdAt":1},null]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())
        assertNull(AgentOperationalMemoryStore.addApproved(context, "tester", "Nowy wniosek po uszkodzeniu magazynu."))
        assertFalse(AgentOperationalMemoryStore.remove(context, "entry-1"))
        AgentOperationalMemoryStore.removeBySource(context, "task-1")
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun approvedOperationalMemoryIsCommittedBeforeReturning() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val entry = AgentOperationalMemoryStore.addApproved(
            context, "tester", "Zatwierdzona reguła do testu trwałego zapisu."
        )!!
        assertTrue(prefs.getString("entries", null).orEmpty().contains(entry.id))
        assertEquals(entry.id, AgentOperationalMemoryStore.all(context).single().id)
    }

    @Test fun malformedStorageDoesNotCrashReads() {
        context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE).edit().putString("entries", "not-json").commit()
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
    }
}
