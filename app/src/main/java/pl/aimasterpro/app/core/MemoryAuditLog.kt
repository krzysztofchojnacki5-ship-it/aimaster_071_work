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
    private const val MAX_TEXT_CHARS = 500

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
     * Adds exactly one event. Existing records are never rewritten or silently discarded.
     * The caller should not perform the associated memory mutation unless this returns true.
     */
    @Synchronized
    fun append(context: Context, event: Event): Boolean = runCatching {
        require(event.actor.isNotBlank() && event.actor.length <= 80)
        require(event.mutation.isNotBlank() && event.mutation.length <= 100)
        require(event.objectId.isNotBlank() && event.objectId.length <= 200)
        require(event.reason.isNotBlank() && event.reason.length <= MAX_TEXT_CHARS)
        require(event.approvalRef.length <= 200)
        require(event.outcome.isNotBlank() && event.outcome.length <= 100)
        require(event.timestamp >= 0L)
        if (SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) return@runCatching false

        val existing = readRecords(context) ?: return@runCatching false
        if (existing.size >= MAX_EVENTS) return@runCatching false
        val previousHash = existing.lastOrNull()?.hash ?: GENESIS_HASH
        val record = Record(
            sequence = existing.size.toLong() + 1L,
            actor = event.actor,
            mutation = event.mutation,
            objectId = event.objectId,
            reason = event.reason,
            approvalRef = event.approvalRef,
            phase = event.phase,
            outcome = event.outcome,
            timestamp = event.timestamp,
            previousHash = previousHash,
            hash = hashRecord(
                existing.size.toLong() + 1L, event.actor, event.mutation, event.objectId,
                event.reason, event.approvalRef, event.phase.name, event.outcome,
                event.timestamp, previousHash
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

    private fun readRecords(context: Context): List<Record>? = runCatching {
        if (SharedPreferencesWriteHealth.isUncertain(PREFS_NAME)) return@runCatching null
        val raw = SharedPreferencesWriteHealth.withHealthyAccess(PREFS_NAME) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(EVENTS_KEY, null)
        } ?: return@runCatching emptyList()
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
            require(actor.isNotBlank() && mutation.isNotBlank() && objectId.isNotBlank())
            require(reason.isNotBlank() && outcome.isNotBlank() && timestamp >= 0L)
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
