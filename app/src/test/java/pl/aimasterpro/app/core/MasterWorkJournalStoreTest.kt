package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MasterWorkJournalStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        SharedPreferencesWriteHealth.resetForTests("ai_master_work_journal_v1")
        context.getSharedPreferences("ai_master_work_journal_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun record(id: String, updated: Long = 1L) = MasterWorkJournalStore.Record(
        taskId = id, chatId = "chat-$id", title = "Audyt AI Master", objective = "Bezpiecznie wznowić pracę.",
        branch = "audit/work", headSha = "abcdef1234567", status = "ACTIVE",
        currentStep = "Kontrola transakcji", completedSteps = listOf("Plan utworzony"),
        decisions = listOf("Nie modyfikować main"), nextSteps = listOf("Uruchomić testy"),
        updatedAt = updated
    )

    @Test fun masterWorkProgressSurvivesReloadAndCanBeResumed() {
        assertTrue(MasterWorkJournalStore.save(context, record("task-a")))
        val resumed = MasterWorkJournalStore.update(context, "task-a") {
            it.copy(status = "PAUSED", completedSteps = it.completedSteps + "Audyt magazynu", updatedAt = 2L)
        }
        assertNotNull(resumed)
        val loaded = MasterWorkJournalStore.get(context, "task-a")!!
        assertEquals("PAUSED", loaded.status)
        assertTrue(loaded.completedSteps.contains("Audyt magazynu"))
        assertEquals(listOf("Uruchomić testy"), loaded.nextSteps)
    }

    @Test fun independentMasterTasksDoNotOverwriteEachOther() {
        val ready = java.util.concurrent.CountDownLatch(16)
        val start = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (1..16).map { index ->
            Thread {
                ready.countDown()
                try {
                    start.await()
                    assertTrue(MasterWorkJournalStore.save(context, record("parallel-$index", index.toLong())))
                } catch (error: Throwable) { failures += error }
            }.apply { start() }
        }
        assertTrue(ready.await(3, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5_000L) }
        assertTrue(workers.none { it.isAlive })
        assertTrue(failures.isEmpty())
        assertEquals(16, MasterWorkJournalStore.latest(context).size)
    }

    @Test fun corruptMasterJournalIsNotReplacedByFreshPlan() {
        val prefs = context.getSharedPreferences("ai_master_work_journal_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"taskId":"broken","status":"ACTIVE"},null]"""
        assertTrue(prefs.edit().putString("records", corrupt).commit())
        assertFalse(MasterWorkJournalStore.save(context, record("new-task")))
        assertNull(MasterWorkJournalStore.get(context, "broken"))
        assertEquals(corrupt, prefs.getString("records", null))
    }

    @Test fun ambiguousMasterJournalCommitQuarantinesFutureAccess() {
        val prefs = context.getSharedPreferences("ai_master_work_journal_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("records", "[]").commit())
        val failingEditor = object : android.content.SharedPreferences.Editor by prefs.edit().putString("records", "[ambiguous]") {
            override fun commit(): Boolean = false
        }
        assertFalse(SharedPreferencesWriteHealth.commit("ai_master_work_journal_v1", failingEditor))
        assertFalse(MasterWorkJournalStore.save(context, record("blocked")))
        assertNull(MasterWorkJournalStore.get(context, "blocked"))
        assertEquals("[]", prefs.getString("records", null))
    }
}
