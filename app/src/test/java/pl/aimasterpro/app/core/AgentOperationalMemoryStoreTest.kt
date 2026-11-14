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

    private fun addLesson(ctx: Context, agentId: String, text: String, category: String = "",
        sourceTaskIds: List<String> = emptyList(), confidence: String = "zatwierdzona"): AgentOperationalMemoryStore.Entry? {
        var result: AgentOperationalMemoryStore.Entry? = null
        val objectId = "test-agent:${java.util.UUID.randomUUID()}"
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER,
            MemoryGovernance.Mutation.ADD_AGENT_LESSON, objectId,
            "Test confirms agent-memory addition", "test-approval")
        val ok = MemoryMutationGate.execute(ctx, request) { permit ->
            result = AgentOperationalMemoryStore.addApproved(ctx, agentId, text, permit, objectId, category, sourceTaskIds, confidence)
            result != null
        }
        return if (ok) result else null
    }
    private fun removeLesson(ctx: Context, id: String): Boolean {
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER,
            MemoryGovernance.Mutation.REMOVE_AGENT_LESSON, id,
            "Test confirms agent-memory removal", "test-approval")
        return MemoryMutationGate.execute(ctx, request) { permit -> AgentOperationalMemoryStore.remove(ctx, id, permit) }
    }
    private fun removeLessonBySource(ctx: Context, source: String): Boolean {
        val request = MemoryGovernance.Request(MemoryGovernance.Actor.USER,
            MemoryGovernance.Mutation.REMOVE_AGENT_LESSON, source,
            "Test confirms source cleanup", "test-approval")
        return MemoryMutationGate.execute(ctx, request) { permit -> AgentOperationalMemoryStore.removeBySource(ctx, source, permit) }
    }

    @Before fun reset() {
        SharedPreferencesWriteHealth.resetForTests("ai_memory_audit_log_v1")
        context.getSharedPreferences("ai_memory_audit_log_v1", Context.MODE_PRIVATE).edit().clear().commit()
        SharedPreferencesWriteHealth.resetForTests("ai_agent_operational_memory_v1")
        context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun proposalPermitCannotMutateAnotherProposalSourceOrForgeProposalProvenance() {
        val foreignSource = "proposal:proposal-foreign"
        val foreignRequest = MemoryGovernance.Request(
            actor = MemoryGovernance.Actor.USER,
            mutation = MemoryGovernance.Mutation.APPROVE_PROPOSAL,
            objectId = "proposal-foreign",
            reason = "Seed a separately approved proposal source.",
            approvalRef = "test-approval:proposal-foreign"
        )
        var seeded: AgentOperationalMemoryStore.Entry? = null
        assertTrue(MemoryMutationGate.execute(context, foreignRequest) { permit ->
            seeded = AgentOperationalMemoryStore.addApproved(
                context, "coder", "Zasada z obcego źródła propozycji.", permit,
                objectId = foreignRequest.objectId, sourceTaskIds = listOf(foreignSource)
            )
            seeded != null
        })

        val request = MemoryGovernance.Request(
            actor = MemoryGovernance.Actor.USER,
            mutation = MemoryGovernance.Mutation.APPROVE_PROPOSAL,
            objectId = "proposal-authorized",
            reason = "Test exact proposal object binding.",
            approvalRef = "test-approval:proposal-authorized"
        )
        var addResult: AgentOperationalMemoryStore.Entry? = null
        assertFalse(MemoryMutationGate.execute(context, request) { permit ->
            addResult = AgentOperationalMemoryStore.addApproved(
                context, "coder", "Nieuprawniona zasada propozycji.", permit,
                objectId = "proposal-foreign", sourceTaskIds = listOf(foreignSource)
            )
            addResult != null
        })
        assertNull(addResult)

        assertFalse(MemoryMutationGate.execute(context, request.copy(
            mutation = MemoryGovernance.Mutation.REJECT_PROPOSAL,
            approvalRef = "test-approval:proposal-authorized-reject"
        )) { permit ->
            AgentOperationalMemoryStore.removeBySource(context, foreignSource, permit)
        })
        assertTrue(AgentOperationalMemoryStore.all(context).any { seeded!!.id == it.id })

        val directRequest = MemoryGovernance.Request(
            actor = MemoryGovernance.Actor.USER,
            mutation = MemoryGovernance.Mutation.ADD_AGENT_LESSON,
            objectId = "manual-entry-foreign-source",
            reason = "Direct lessons cannot forge proposal provenance.",
            approvalRef = "test-approval:manual-entry-foreign-source"
        )
        assertFalse(MemoryMutationGate.execute(context, directRequest) { permit ->
            AgentOperationalMemoryStore.addApproved(
                context, "coder", "Zasada z podszytym źródłem propozycji.", permit,
                objectId = directRequest.objectId, sourceTaskIds = listOf(foreignSource)
            ) != null
        })
    }

    @Test fun memoryIsScopedToTheOwningAgent() {
        addLesson(context, "code-analyst", "W Kotlin sprawdzaj null safety oraz testy jednostkowe.")
        assertTrue(AgentOperationalMemoryStore.contextString(context, "code-analyst", "Kotlin null safety", 900).contains("null safety"))
        assertEquals("", AgentOperationalMemoryStore.contextString(context, "security-agent", "Kotlin null safety", 900))
    }

    @Test fun retrievalIsRelevantAndBounded() {
        addLesson(context, "tester", "Dla testów Gradle uruchamiaj testDebugUnitTest przed assembleDebug.")
        addLesson(context, "tester", "Przy grafice sprawdzaj proporcje obrazu.")
        val result = AgentOperationalMemoryStore.contextString(context, "tester", "Gradle testDebugUnitTest", maxChars = 300, maxItems = 4)
        assertTrue(result.length <= 300)
        assertTrue(result.contains("testDebugUnitTest"))
        assertFalse(result.contains("proporcje obrazu"))
    }

    @Test fun duplicateEntriesMergeTheirProvenanceAndCanBeRemoved() {
        val first = addLesson(context, "architect", "Zachowuj kompatybilność API przy refaktoryzacji.", sourceTaskIds = listOf("task-a"))!!
        val second = addLesson(context, "architect", "Zachowuj kompatybilność API przy refaktoryzacji.", sourceTaskIds = listOf("task-b"))!!
        assertEquals(first.id, second.id)
        assertEquals(setOf("task-a", "task-b"), second.sourceTaskIds.toSet())
        removeLessonBySource(context, "task-a")
        assertEquals(listOf("task-b"), AgentOperationalMemoryStore.all(context).single().sourceTaskIds)
        assertTrue(removeLesson(context, first.id))
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
    }

    @Test fun malformedOperationalMemoryIsNotOverwrittenByMutation() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"id":"entry-1","agentId":"tester","text":"valid row","createdAt":1},null]"""
        assertTrue(prefs.edit().putString("entries", corrupt).commit())
        assertNull(addLesson(context, "tester", "Nowy wniosek po uszkodzeniu magazynu."))
        assertFalse(removeLesson(context, "entry-1"))
        removeLessonBySource(context, "task-1")
        assertEquals(corrupt, prefs.getString("entries", null))
    }

    @Test fun fractionalTimestampAndMalformedProvenanceInvalidateWholeMemory() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val fractional = """[{"id":"entry-1","agentId":"tester","text":"valid memory entry","createdAt":1.5}]"""
        assertTrue(prefs.edit().putString("entries", fractional).commit())
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
        assertNull(addLesson(context, "tester", "A nowa reguła po błędzie.") )
        assertEquals(fractional, prefs.getString("entries", null))
        val malformedSources = """[{"id":"entry-2","agentId":"tester","text":"valid memory entry","createdAt":1,"sourceTaskIds":[7]}]"""
        assertTrue(prefs.edit().putString("entries", malformedSources).commit())
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
        assertEquals(malformedSources, prefs.getString("entries", null))
    }

    @Test fun approvedOperationalMemoryIsCommittedBeforeReturning() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val entry = addLesson(
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
        assertNull(addLesson(
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
        assertNull(addLesson(context, "tester", "Nie zapisuj po niepewnym commit."))
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
                    assertNotNull(addLesson(
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
            addLesson(
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

    private fun org.json.JSONObject.deepCopy(): org.json.JSONObject = org.json.JSONObject(toString())

    @Test fun oversizedPersistedFieldsFailClosedAndAreNeverTruncatedOnRewrite() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val validRow = org.json.JSONObject()
            .put("id", "entry-valid")
            .put("agentId", "tester")
            .put("text", "Poprawny tekst wpisu pamięci operacyjnej.")
            .put("category", "")
            .put("createdAt", 1L)
            .put("sourceTaskIds", org.json.JSONArray())
            .put("confidence", "zatwierdzona")
        val malformedRows = listOf(
            validRow.deepCopy().put("id", "i".repeat(121)),
            validRow.deepCopy().put("agentId", "a".repeat(101)),
            validRow.deepCopy().put("text", "x".repeat(1201)),
            validRow.deepCopy().put("category", "c".repeat(81)),
            validRow.deepCopy().put("confidence", "v".repeat(41)),
            validRow.deepCopy().put("sourceTaskIds", org.json.JSONArray().put("s".repeat(201)))
        )
        malformedRows.forEach { row ->
            val corrupt = org.json.JSONArray().put(row).toString()
            assertTrue(prefs.edit().putString("entries", corrupt).commit())
            assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
            assertNull(addLesson(context, "tester", "Nowy wpis nie może nadpisać niepoprawnego rekordu."))
            assertEquals(corrupt, prefs.getString("entries", null))
            SharedPreferencesWriteHealth.resetForTests("ai_agent_operational_memory_v1")
        }
    }

    @Test fun newLessonsRejectOversizedOrMalformedInputInsteadOfSilentlyTruncating() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        assertNull(addLesson(context, "a".repeat(101), "Poprawny tekst reguły do zapisania."))
        assertNull(addLesson(context, "tester", "Poprawny tekst reguły do zapisania.", category = "c".repeat(81)))
        assertNull(addLesson(context, "tester", "Poprawny tekst reguły do zapisania.", sourceTaskIds = listOf(" ")))
        assertNull(addLesson(context, "tester", "Poprawny tekst reguły do zapisania.", sourceTaskIds = listOf("s".repeat(201))))
        assertNull(addLesson(context, "tester", "x".repeat(1201)))
        assertNull(prefs.getString("entries", null))
    }

    @Test fun provenanceMergeRefusesToDropSourceIdsWhenCapacityIsReached() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val text = "Zachowuj pełną historię źródeł zatwierdzonej reguły."
        val firstSources = (1..30).map { "source-$it" }
        val first = addLesson(context, "architect", text, sourceTaskIds = firstSources)!!
        val before = prefs.getString("entries", null)
        assertNull(addLesson(context, "architect", text, sourceTaskIds = listOf("source-31")))
        assertEquals(before, prefs.getString("entries", null))
        assertEquals(firstSources.toSet(), AgentOperationalMemoryStore.all(context).single().sourceTaskIds.toSet())
        assertEquals(first.id, AgentOperationalMemoryStore.all(context).single().id)
    }

    @Test fun oversizedRawOperationalMemoryIsRejectedBeforeParsingAndPreserved() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val oversized = " ".repeat(1_000_001)
        assertTrue(prefs.edit().putString("entries", oversized).commit())

        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
        assertNull(addLesson(context, "tester", "Nowy wpis nie może nadpisać zbyt dużej pamięci."))
        assertEquals(oversized, prefs.getString("entries", null))
    }


    @Test fun approvedLessonTextIsSanitizedBeforePersistence() {
        val entry = addLesson(context, "security-agent",
            "Nie zapisuj sekretu api_key=sk_live_1234567890 w pamięci operacyjnej.")!!
        assertFalse(entry.text.contains("sk_live_1234567890"))
        assertTrue(entry.text.contains("[REDACTED]"))
        assertEquals(entry.text, AgentOperationalMemoryStore.all(context).single().text)
    }

    @Test fun sanitizerExpansionBeyondFieldLimitRejectsLessonWithoutTruncation() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val raw = "api_key=x ".repeat(110)
        assertTrue(raw.length <= 1_200)
        assertTrue(CommunicationLogSanitizer.sanitize(raw).length > 1_200)
        assertNull(addLesson(context, "security-agent", raw))
        assertNull(prefs.getString("entries", null))
    }

    @Test fun metadataIsSanitizedAndPersistedMetadataMustAlreadyBeSafe() {
        val entry = addLesson(context, "security-agent",
            "Nie ujawniaj danych dostępowych podczas analizy logów.",
            category = "api_key=sk_live_1234567890",
            confidence = "ghp_12345678901234567890")!!
        assertFalse(entry.category.contains("sk_live_"))
        assertFalse(entry.confidence.contains("ghp_"))
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        val tampered = org.json.JSONArray().put(org.json.JSONObject()
            .put("id", "entry-secret-meta").put("agentId", "security-agent")
            .put("text", "Nie ujawniaj danych dostępowych podczas analizy logów.")
            .put("category", "api_key=sk_live_1234567890").put("createdAt", 1L)
            .put("sourceTaskIds", org.json.JSONArray()).put("confidence", "zatwierdzona")).toString()
        assertTrue(prefs.edit().putString("entries", tampered).commit())
        assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
        assertNull(addLesson(context, "security-agent", "Nie zapisuj niczego nad uszkodzonym rejestrem pamięci."))
        assertEquals(tampered, prefs.getString("entries", null))
    }

    @Test fun sourceApprovalLookupRejectsOversizedTextInsteadOfMatchingItsTruncatedPrefix() {
        val lesson = "Zachowuj dokładną treść zatwierdzonej reguły."
        val entry = addLesson(context, "architect", lesson, sourceTaskIds = listOf("source-exact"))!!
        val oversized = lesson + " " + "x".repeat(1_300)
        val result = AgentOperationalMemoryStore.hasApprovedSource(
            context, "architect", oversized, "source-exact"
        )
        assertFalse(result == true)
        assertTrue(AgentOperationalMemoryStore.hasApprovedSource(
            context, "architect", lesson, "source-exact"
        ) == true)
        assertEquals(entry.id, AgentOperationalMemoryStore.all(context).single().id)
    }

    @Test fun zeroAndNegativePersistedTimestampsAreRejectedWithoutOverwrite() {
        val prefs = context.getSharedPreferences("ai_agent_operational_memory_v1", Context.MODE_PRIVATE)
        listOf(0L, -1L).forEach { timestamp ->
            val raw = org.json.JSONArray().put(org.json.JSONObject()
                .put("id", "entry-invalid-time").put("agentId", "tester")
                .put("text", "Prawidłowy tekst wpisu pamięci.").put("createdAt", timestamp)
                .put("sourceTaskIds", org.json.JSONArray())).toString()
            assertTrue(prefs.edit().putString("entries", raw).commit())
            assertTrue(AgentOperationalMemoryStore.all(context).isEmpty())
            assertNull(addLesson(context, "tester", "Nowy wpis nie może zastąpić nieprawidłowego czasu."))
            assertEquals(raw, prefs.getString("entries", null))
            SharedPreferencesWriteHealth.resetForTests("ai_agent_operational_memory_v1")
        }
    }
}
