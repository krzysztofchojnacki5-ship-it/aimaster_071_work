package pl.aimasterpro.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Append-only, hash-chained journal for memory-governance decisions.
 *
 * A journal write is deliberately fail-closed: malformed history, a broken hash chain,
 * an uncertain SharedPreferences file, or a full journal prevents further appends.
 * Callers must append INTENT before a governed mutation and a terminal event afterwards.
 */
object MemoryAuditLog {
    private const val PREFS_NAME = "ai_memory_audit_log_v1"
    private const val EVENTS_KEY = "events"
    private const val MAX_EVENTS = 5_000
    private const val MAX_RAW_CHARS = 4_000_000
    private const val MAX_ACTOR_CHARS = 80
    private const val MAX_MUTATION_CHARS = 100
    private const val MAX_OBJECT_ID_CHARS = 200
    private const val MAX_TEXT_CHARS = 500
    private const val MAX_APPROVAL_REF_CHARS = 200
    private const val MAX_OUTCOME_CHARS = 100

    enum class Phase { INTENT, COMMITTED, FAILED }

    data class Event(
        val actor: String,
        val mutation: String,
        val objectId: String,
        val reason: String,
        val approvalRef: String,
        val phase: Phase,
        val outcome: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class Record(
        val sequence: Long,
        val actor: String,
        val mutation: String,
        val objectId: String,
        val reason: String,
        val approvalRef: String,
        val phase: Phase,
        val outcome: String,
        val timestamp: Long,
        val previousHash: String,
        val hash: String
    )

    /** Null means the persisted journal is malformed or its integrity chain is broken. */
    @Synchronized
    fun readVerified(context: Context): List<Record>? = readRecords(context)

    /**
     * Detects a previously committed USER request with the same approval identity.
     * An unreadable journal is treated as a duplicate so callers fail closed.
     */
    @Synchronized
    internal fun hasCompletedRequest(
        context: Context,
        actor: String,
        mutation: String,
        objectId: String,
        approvalRef: String
    ): Boolean {
        if (actor != "USER") return false
        val records = readRecords(context) ?: return true
        return records.any {
            it.actor == actor && it.mutation == mutation && it.objectId == objectId &&
                it.approvalRef == approvalRef && it.phase == Phase.COMMITTED
        }
    }

    /**
     * Detects an operation whose durable INTENT has no terminal record.
     * Stable identity excludes the reason and approval reference, which can change on retry.
     */
    @Synchronized
    internal fun hasUnresolvedIntent(
        context: Context,
        actor: String,
        mutation: String,
        objectId: String
    ): Boolean {
        val matching = readRecords(context) ?: return true
        val latest = matching.lastOrNull {
            it.actor == actor && it.mutation == mutation && it.objectId == objectId &&
                !it.outcome.startsWith("DENIED_")
        } ?: return false
        // A verified recovery terminal record also resolves the original operation.
        return latest.phase != Phase.COMMITTED
    }

    /**
     * Returns the latest unresolved record for each operation identity. A null result means
     * the journal cannot be trusted and recovery must stop without changing any store.
     */
    @Synchronized
    internal fun unresolvedOperations(context: Context, objectId: String? = null): List<Record>? {
        val records = readRecords(context) ?: return null
        val latestByIdentity = linkedMapOf<Triple<String, String, String>, Record>()
        records.forEach { record ->
            if (record.outcome.startsWith("DENIED_")) return@forEach
            if (objectId != null && record.objectId != objectId) return@forEach
            latestByIdentity[Triple(record.actor, record.mutation, record.objectId)] = record
        }
        return latestByIdentity.values.filter { it.phase != Phase.COMMITTED }
    }

    /**
     * Closes an unresolved operation only after its desired durable state has been verified.
     * The terminal event keeps the original operation identity and explicitly records recovery.
     */
    @Synchronized
    internal fun resolveUnresolved(context: Context, pending: Record, reason: String): Boolean {
        if (pending.actor !in setOf("USER", "SYSTEM_RECOVERY") || reason.isBlank()) return false
        val latest = unresolvedOperations(context, pending.objectId)?.lastOrNull {
            it.actor == pending.actor && it.mutation == pending.mutation && it.objectId == pending.objectId
        } ?: return false
        if (latest.sequence != pending.sequence || latest.phase == Phase.COMMITTED) return false
        return append(context, Event(
            actor = pending.actor,
            mutation = pending.mutation,
            objectId = pending.objectId,
            reason = reason.take(MAX_TEXT_CHARS),
            approvalRef = pending.approvalRef,
            phase = Phase.COMMITTED,
            outcome = "RECOVERED_STATE"
        ))
    }

    /**
     * Adds exactly one event. Existing records are never rewritten or silently discarded.
     * The caller should not perform the associated memory mutation unless this returns true.
     */
    @Synchronized
    fun append(context: Context, event: Event): Boolean = runCatching {
        val safeEvent = event.copy(reason = CommunicationLogSanitizer.sanitize(event.reason))
        require(safeEvent.actor.isNotBlank() && safeEvent.actor.length <= MAX_ACTOR_CHARS)
        require(safeEvent.mutation.isNotBlank() && safeEvent.mutation.length <= MAX_MUTATION_CHARS)
        require(safeEvent.objectId.isNotBlank() && safeEvent.objectId.length <= MAX_OBJECT_ID_CHARS)
        require(safeEvent.reason.isNotBlank() && safeEvent.reason.length <= MAX_TEXT_CHARS)
        require(safeEvent.approvalRef.length <= MAX_APPROVAL_REF_CHARS)
        require(safeEvent.outcome.isNotBlank() && safeEvent.outcome.length <= MAX_OUTCOME_CHARS)
        require(safeEvent.timestamp >= 0L)
        // Identity and control fields must remain byte-for-byte stable; only free-form
        // audit reasons may be redacted, otherwise the operation identity could be aliased.
        require(CommunicationLogSanitizer.sanitize(safeEvent.actor) == safeEvent.actor)
        require(CommunicationLogSanitizer.sanitize(safeEvent.mutation) == safeEvent.mutation)
        require(CommunicationLogSanitizer.sanitize(safeEvent.objectId) == safeEvent.objectId)
        require(CommunicationLogSanitizer.sanitize(safeEvent.approvalRef) == safeEvent.approvalRef)
        require(CommunicationLogSanitizer.sanitize(safeEvent.outcome) == safeEvent.outcome)
        if (SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) return@runCatching false

        val existing = readRecords(context) ?: return@runCatching false
        if (!hasReservedCapacity(existing, safeEvent)) return@runCatching false
        val previousHash = existing.lastOrNull()?.hash ?: GENESIS_HASH
        val record = Record(
            sequence = existing.size.toLong() + 1L,
            actor = safeEvent.actor,
            mutation = safeEvent.mutation,
            objectId = safeEvent.objectId,
            reason = safeEvent.reason,
            approvalRef = safeEvent.approvalRef,
            phase = safeEvent.phase,
            outcome = safeEvent.outcome,
            timestamp = safeEvent.timestamp,
            previousHash = previousHash,
            hash = hashRecord(
                existing.size.toLong() + 1L, safeEvent.actor, safeEvent.mutation, safeEvent.objectId,
                safeEvent.reason, safeEvent.approvalRef, safeEvent.phase.name, safeEvent.outcome,
                safeEvent.timestamp, previousHash
            )
        )
        val rows = JSONArray()
        existing.forEach { rows.put(toJson(it)) }
        rows.put(toJson(record))
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        SharedPreferencesWriteHealth.commit(
            PREFS_NAME, prefs.edit().putString(EVENTS_KEY, rows.toString())
        )
    }.getOrDefault(false)

    /**
     * Reserves capacity for a complete USER mutation and its proposal-recovery lineage.
     *
     * A USER INTENT reserves five future records: its ordinary terminal record, plus the
     * four records needed for a worst-case recovery (SYSTEM_RECOVERY INTENT, failed recovery
     * terminal, verified recovery closure, and USER RECOVERED_STATE).
     * Once recovery starts before a USER terminal exists, the unused ordinary-terminal
     * branch is released. Recovery records tied to the same proposal consume the USER
     * reservation instead of creating a second, overlapping reservation.
     *
     * A standalone INTENT reserves its terminal and one possible manual resolution.
     * FAILED retains one manual-resolution slot. Denial records never mask unresolved work.
     * All accounting is performed under append's monitor.
     */
    private fun hasReservedCapacity(existing: List<Record>, event: Event): Boolean {
        val candidate = existing + Record(
            sequence = existing.size.toLong() + 1L,
            actor = event.actor,
            mutation = event.mutation,
            objectId = event.objectId,
            reason = event.reason,
            approvalRef = event.approvalRef,
            phase = event.phase,
            outcome = event.outcome,
            timestamp = event.timestamp,
            previousHash = "",
            hash = ""
        )

        val latestByIdentity = linkedMapOf<Triple<String, String, String>, Record>()
        candidate.forEach { record ->
            if (!record.outcome.startsWith("DENIED_")) {
                latestByIdentity[Triple(record.actor, record.mutation, record.objectId)] = record
            }
        }

        if (!event.outcome.startsWith("DENIED_")) {
            val previous = existing.lastOrNull {
                it.actor == event.actor && it.mutation == event.mutation &&
                    it.objectId == event.objectId && !it.outcome.startsWith("DENIED_")
            }
            // A second INTENT for an unresolved identity would create an ambiguous
            // reservation and is rejected; callers must resolve the earlier attempt first.
            if (event.phase == Phase.INTENT && previous != null && previous.phase != Phase.COMMITTED) {
                return false
            }
        }

        val unresolvedUsers = latestByIdentity.values.filter {
            it.actor == "USER" && it.phase != Phase.COMMITTED &&
                it.mutation in setOf("APPROVE_PROPOSAL", "REJECT_PROPOSAL")
        }
        val linkedRecoveryKeys = mutableSetOf<Triple<String, String, String>>()
        var reservedSlots = 0

        unresolvedUsers.forEach { user ->
            val recovery = candidate.filter {
                it.actor == "SYSTEM_RECOVERY" && it.objectId == user.objectId &&
                    it.approvalRef == "proposal:${user.objectId}" &&
                    it.mutation in setOf("RECONCILE_APPROVED_PROPOSAL", "RECONCILE_REVOKED_PROPOSAL") &&
                    it.sequence > user.sequence
            }
            recovery.forEach {
                linkedRecoveryKeys += Triple(it.actor, it.mutation, it.objectId)
            }
            val latestRecovery = recovery.lastOrNull()
            val remaining = when {
                recovery.isEmpty() -> if (user.phase == Phase.INTENT) 5 else 4
                latestRecovery?.phase == Phase.INTENT -> 3 // terminal + two possible closures
                latestRecovery?.phase == Phase.FAILED -> 2 // verified recovery closure + USER closure
                else -> 1 // recovery is committed; only the original USER closure remains
            }
            reservedSlots += remaining
        }

        latestByIdentity.forEach { (identity, record) ->
            if (identity in linkedRecoveryKeys) return@forEach
            if (record.actor == "USER" && record in unresolvedUsers) return@forEach
            reservedSlots += when (record.phase) {
                Phase.INTENT -> 2
                Phase.FAILED -> 1
                Phase.COMMITTED -> 0
            }
        }
        return candidate.size + reservedSlots <= MAX_EVENTS
    }

    private fun readRecords(context: Context): List<Record>? = runCatching {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) return@runCatching null
        val raw = SharedPreferencesWriteHealth.withHealthyAccess(PREFS_NAME) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(EVENTS_KEY, null)
        } ?: return@runCatching emptyList()
        require(raw.length <= MAX_RAW_CHARS) { "Memory audit journal exceeds its raw size limit." }
        val rows = JSONArray(raw)
        require(rows.length() <= MAX_EVENTS)
        val result = ArrayList<Record>(rows.length())
        var expectedPrevious = GENESIS_HASH
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val sequence = row.getLong("sequence")
            val actor = row.getString("actor")
            val mutation = row.getString("mutation")
            val objectId = row.getString("objectId")
            val reason = row.getString("reason")
            val approvalRef = row.getString("approvalRef")
            val phase = Phase.valueOf(row.getString("phase"))
            val outcome = row.getString("outcome")
            val timestamp = row.getLong("timestamp")
            val previousHash = row.getString("previousHash")
            val hash = row.getString("hash")
            require(sequence == index.toLong() + 1L)
            require(previousHash == expectedPrevious)
            require(actor.isNotBlank() && actor.length <= MAX_ACTOR_CHARS)
            require(mutation.isNotBlank() && mutation.length <= MAX_MUTATION_CHARS)
            require(objectId.isNotBlank() && objectId.length <= MAX_OBJECT_ID_CHARS)
            require(reason.isNotBlank() && reason.length <= MAX_TEXT_CHARS)
            require(approvalRef.length <= MAX_APPROVAL_REF_CHARS)
            require(outcome.isNotBlank() && outcome.length <= MAX_OUTCOME_CHARS)
            // Refuse historical or externally edited rows that contain unredacted secrets.
            require(CommunicationLogSanitizer.sanitize(actor) == actor)
            require(CommunicationLogSanitizer.sanitize(mutation) == mutation)
            require(CommunicationLogSanitizer.sanitize(objectId) == objectId)
            require(CommunicationLogSanitizer.sanitize(reason) == reason)
            require(CommunicationLogSanitizer.sanitize(approvalRef) == approvalRef)
            require(CommunicationLogSanitizer.sanitize(outcome) == outcome)
            require(timestamp >= 0L)
            require(hash.matches(Regex("[0-9a-f]{64}")))
            require(hash == hashRecord(
                sequence, actor, mutation, objectId, reason, approvalRef, phase.name,
                outcome, timestamp, previousHash
            ))
            result.add(Record(sequence, actor, mutation, objectId, reason, approvalRef,
                phase, outcome, timestamp, previousHash, hash))
            expectedPrevious = hash
        }
        result
    }.getOrNull()

    private fun toJson(record: Record) = JSONObject()
        .put("sequence", record.sequence)
        .put("actor", record.actor)
        .put("mutation", record.mutation)
        .put("objectId", record.objectId)
        .put("reason", record.reason)
        .put("approvalRef", record.approvalRef)
        .put("phase", record.phase.name)
        .put("outcome", record.outcome)
        .put("timestamp", record.timestamp)
        .put("previousHash", record.previousHash)
        .put("hash", record.hash)

    private fun hashRecord(
        sequence: Long, actor: String, mutation: String, objectId: String, reason: String,
        approvalRef: String, phase: String, outcome: String, timestamp: Long, previousHash: String
    ): String {
        // Length-prefixing prevents delimiter ambiguity in attacker-controlled text fields.
        val values = listOf(
            sequence.toString(), actor, mutation, objectId, reason, approvalRef,
            phase, outcome, timestamp.toString(), previousHash
        )
        val canonical = values.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private const val GENESIS_HASH = "GENESIS"
}
