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
        SharedPreferencesWriteHealth.resetForTests("ai_agent_operational_memory_v1")
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

    @Test fun fractionalTimestampAndMalformedProvenanceInvalidateWholeMemory() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val fractional = """[{"id":"entry-1","agentId":"tester","text":"valid memory entry","createdAt":1.5}]"""
        assertTrue(prefs.edit().putString("entries", fractional).commit())
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
        assertNull(AgentOperationalMemoryStore.addApproved(context, "tester", "A nowa reguła po błędzie.") )
        assertEquals(fractional, prefs.getString("entries", null))
        val malformedSources = """[{"id":"entry-2","agentId":"tester","text":"valid memory entry","createdAt":1,"sourceTaskIds":[7]}]"""
        assertTrue(prefs.edit().putString("entries", malformedSources).commit())
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
        assertEquals(malformedSources, prefs.getString("entries", null))
    }

    @Test fun approvedOperationalMemoryIsCommittedBeforeReturning() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val entry = AgentOperationalMemoryStore.addApproved(
            context, "tester", "Zatwierdzona reguła do testu trwałego zapisu."
        )!!
        assertTrue(prefs.getString("entries", null).orEmpty().contains(entry.id))
        assertEquals(entry.id, AgentOperationalMemoryStore.all(context).single().id)
    }

    @Test fun preferenceAccessFailureDoesNotCrashOrPretendApprovedMemoryWasSaved() {
        val failingContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                throw IllegalStateException("controlled operational memory preferences failure")
            }
        }
        assertNull(AgentOperationalMemoryStore.addApproved(
            failingContext, "tester", "Ta zatwierdzona reguła nie może zostać utracona."
        ))
        assertTrue(AgentOperationalMemoryStore.all(failingContext).isEmpty())
    }

    @Test fun malformedStorageDoesNotCrashReads() {
        context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE).edit().putString("entries", "not-json").commit()
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
    }
    @Test fun ambiguousCommitQuarantinesOperationalMemoryUntilProcessRestart() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val original = "[]"
        assertTrue(prefs.edit().putString("entries", original).commit())
        val failingEditor = object : android.content.SharedPreferences.Editor by prefs.edit().putString("entries", "[ambiguous]") {
            override fun commit(): Boolean = false
        }
        assertFalse(SharedPreferencesWriteHealth.commit("ai_agent_operational_memory_v1", failingEditor))

        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
        assertNull(AgentOperationalMemoryStore.addApproved(context, "tester", "Nie zapisuj po niepewnym commit."))
        assertEquals(original, prefs.getString("entries", null))
    }
    @Test fun concurrentApprovalsMergeEverySourceTaskWithoutLostUpdates() {
        val ready = java.util.concurrent.CountDownLatch(12)
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (1..12).map { index ->
            Thread {
                ready.countDown()
                try {
                    start.await()
                    assertNotNull(AgentOperationalMemoryStore.addApproved(
                        context, "architect", "Zachowuj atomowy zapis pamięci przy równoległych zatwierdzeniach.",
                        sourceTaskIds = listOf("source-$index")
                    ))
                } catch (error: Throwable) {
                    failures += error
                }
            }.apply { start() }
        }
        assertTrue("Memory workers did not become ready", ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5_000L) }
        assertTrue("Memory workers did not finish", workers.none { it.isAlive })
        assertTrue("Memory worker failed: $failures", failures.isEmpty())

        val entries = AgentOperationalMemoryStore.all(context)
        assertEquals("Identical approved lessons must be merged into one entry", 1, entries.size)
        assertEquals((1..12).map { "source-$it" }.toSet(), entries.single().sourceTaskIds.toSet())
    }

    @Test fun mutationWaitsForSharedPreferenceLockAcrossReadModifyWrite() {
        val ready = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            ready.countDown()
            AgentOperationalMemoryStore.addApproved(
                context, "tester", "Zapis pamięci musi zachować pełny cykl transakcyjny.",
                sourceTaskIds = listOf("locked-source")
            )
            finished.countDown()
        }
        SharedPreferencesWriteHealth.withHealthyAccess("ai_agent_operational_memory_v1") {
            worker.start()
            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("Memory mutation must wait while its preference file is locked",
                finished.await(150, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
        worker.join(3_000L)
        assertTrue("Memory mutation did not finish after lock release", finished.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("locked-source"), AgentOperationalMemoryStore.all(context).single().sourceTaskIds)
    }

}
