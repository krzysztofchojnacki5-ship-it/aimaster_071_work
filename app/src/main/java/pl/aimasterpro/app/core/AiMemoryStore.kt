package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * Persistent user-approved rules and learning. Context injection is relevance-ranked and bounded
 * so a large memory library does not inflate every model request.
 */
object AiMemoryStore {
    private const val PREFS_NAME = "ai_master_memory_store_v1"
    private const val MAX_MEMORIES = 100
    private const val MAX_MEMORY_CHARS = 2_000
    private const val MAX_MEMORY_RAW_CHARS = 250_000
    private const val DEFAULT_CONTEXT_LIMIT = 2_400
    private const val DEFAULT_CONTEXT_ITEMS = 8
    private val termRegex = Regex("[\\p{L}\\p{N}]{3,}")
    private val stopWords = setOf(
        "oraz", "który", "która", "które", "jest", "być", "tego", "taka", "takie", "żeby", "przez",
        "przy", "jako", "jego", "jej", "dla", "czyli", "oraz", "the", "and", "for", "with", "from",
        "this", "that", "have", "will", "your", "into", "when", "what", "how"
    )

    fun getMemories(context: Context): List<String> =
        readStoredMemories(context)?.ifEmpty { defaultMemories() } ?: defaultMemories()

    /**
     * A missing preference is a valid first-run state. Corrupt JSON is not an empty memory
     * library: mutation callers must stop rather than replace the unreadable source.
     */
    private fun readStoredMemories(context: Context): List<String>? = runCatching {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) return@runCatching null
        val raw = SharedPreferencesWriteHealth.withHealthyAccess(PREFS_NAME) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("memories", null)
        } ?: return@runCatching defaultMemories()
        require(raw.length <= MAX_MEMORY_RAW_CHARS) { "Memory library exceeds its raw size limit." }
        val arr = JSONArray(raw)
        require(arr.length() <= MAX_MEMORIES) { "Memory library exceeds its supported capacity." }
        val values = (0 until arr.length()).map { index ->
            val value = arr.get(index)
            require(value is String && value.isNotBlank() && value.length <= MAX_MEMORY_CHARS) {
                "Memory entry is blank or exceeds its supported size."
            }
            value
        }
        require(values.distinct().size == values.size) { "Memory library contains duplicate entries." }
        values
    }.getOrNull()

    /** Null means the persisted memory list is unreadable; false means the exact entry is absent. */
    @Synchronized
    internal fun containsExactMemory(context: Context, exactMemory: String): Boolean? =
        readStoredMemories(context)?.contains(exactMemory)

    @Synchronized
    internal fun addMemory(context: Context, memory: String, permit: MemoryMutationPermit): Boolean {
        if (!MemoryMutationGate.isValid(permit, setOf(
                MemoryGovernance.Mutation.ADD_SHARED_RULE,
                MemoryGovernance.Mutation.APPROVE_PROPOSAL,
                MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL
            ))) return false
        return addMemoryRaw(context, memory)
    }

    @Synchronized
    private fun addMemoryRaw(context: Context, memory: String): Boolean = runCatching {
        val cleanMemory = memory.trim()
        if (cleanMemory.isBlank() || cleanMemory.length > MAX_MEMORY_CHARS ||
            SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) return@runCatching false
        val list = readStoredMemories(context)?.toMutableList() ?: return@runCatching false
        if (list.contains(cleanMemory)) return@runCatching true
        if (list.size >= MAX_MEMORIES) return@runCatching false
        list.add(0, cleanMemory)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("memories", arr.toString())
            .let { SharedPreferencesWriteHealth.commit(PREFS_NAME, it) }
    }.getOrDefault(false)

    @Synchronized
    internal fun removeMemory(context: Context, exactMemory: String, permit: MemoryMutationPermit): Boolean {
        if (!MemoryMutationGate.isValid(permit, setOf(
                MemoryGovernance.Mutation.REMOVE_SHARED_RULE,
                MemoryGovernance.Mutation.APPROVE_PROPOSAL,
                MemoryGovernance.Mutation.REJECT_PROPOSAL,
                MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL,
                MemoryGovernance.Mutation.RECONCILE_REVOKED_PROPOSAL
            ))) return false
        // Proposal rollback and startup reconciliation are idempotent: an already-absent
        // entry is the desired state. A direct user deletion remains strict and returns false.
        val idempotentRemoval = setOf(
            MemoryGovernance.Mutation.APPROVE_PROPOSAL,
            MemoryGovernance.Mutation.REJECT_PROPOSAL,
            MemoryGovernance.Mutation.RECONCILE_APPROVED_PROPOSAL,
            MemoryGovernance.Mutation.RECONCILE_REVOKED_PROPOSAL
        ).any { MemoryMutationGate.isValid(permit, setOf(it)) }
        return removeMemoryRaw(context, exactMemory, missingIsSuccess = idempotentRemoval)
    }

    @Synchronized
    private fun removeMemoryRaw(
        context: Context, exactMemory: String, missingIsSuccess: Boolean
    ): Boolean = runCatching {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) return@runCatching false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = readStoredMemories(context) ?: return@runCatching false
        if (exactMemory !in current) return@runCatching missingIsSuccess
        val remaining = current.filterNot { it == exactMemory }
        val arr = JSONArray()
        remaining.forEach { arr.put(it) }
        // Persist removals from built-in defaults even before the first preferences write.
        SharedPreferencesWriteHealth.commit(PREFS_NAME, prefs.edit().putString("memories", arr.toString()))
    }.getOrDefault(false)

    /**
     * Selects a small set of memories relevant to the current request. Operational counters are
     * intentionally excluded by default: they are not instructions and waste context tokens.
     */
    fun contextString(
        context: Context,
        query: String = "",
        maxChars: Int = DEFAULT_CONTEXT_LIMIT,
        maxItems: Int = DEFAULT_CONTEXT_ITEMS,
        includeOperationalSummary: Boolean = false
    ): String {
        if (maxChars <= 0 || maxItems <= 0) return ""
        val memories = getMemories(context)
        val queryTerms = terms(query)
        val ranked = memories.mapIndexed { index, memory ->
            val memoryTerms = terms(memory)
            val score = if (queryTerms.isEmpty()) 0 else queryTerms.count { it in memoryTerms }
            Triple(index, memory, score)
        }
        val coreRules = defaultMemories().take(2).mapNotNull { rule ->
            ranked.firstOrNull { it.second == rule }
        }
        val relevant = ranked.filter { it !in coreRules }
            .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
        val selected = (coreRules + relevant).distinctBy { it.second }.take(maxItems)
        val header = "ZASADY I PAMIĘĆ ZATWIERDZONA (wybrane pod bieżące zadanie):\n"
        val out = StringBuilder(header.take(maxChars))
        selected.forEachIndexed { index, item ->
            if (out.length >= maxChars) return@forEachIndexed
            val prefix = if (index == 0) "• " else "\n• "
            val available = maxChars - out.length - prefix.length
            if (available <= 0) return@forEachIndexed
            val line = item.second.replace('\n', ' ').trim()
            out.append(prefix)
            out.append(line.take(available))
        }
        if (includeOperationalSummary && out.length < maxChars) {
            val operational = LearningJournalStore.contextString(context)
            if (operational.isNotBlank()) {
                val separator = "\n\nMETRYKI OPERACYJNE (nie są oceną jakości):\n"
                val remaining = (maxChars - out.length - separator.length).coerceAtLeast(0)
                if (remaining > 0) out.append(separator).append(operational.take(remaining))
            }
        }
        return out.toString().take(maxChars)
    }

    private fun terms(value: String): Set<String> = termRegex
        .findAll(value.lowercase(Locale.ROOT))
        .map { it.value }
        .filterNot { it in stopWords }
        .toSet()

    private fun defaultMemories(): List<String> = listOf(
        "1. Ucz się na wynikach każdego zadania: zapisuj i weryfikuj poprawki przed ich zatwierdzeniem.",
        "2. Oddzielaj potwierdzone fakty z kodu źródłowego od hipotez i domysłów.",
        "3. Pamiętaj o architekturze projektu: Single-Activity Kotlin, Android Keystore, TeamRunEngine dla paralelizacji agentów.",
        "4. Zawsze weryfikuj limity tokenów i automatycznie stosuj failover przy błędach 429.",
        "5. Zachowaj czystość kodu, poprawne formatowanie i przestrzegaj zasad bezpieczeństwa RODO/GDPR."
    )
}
