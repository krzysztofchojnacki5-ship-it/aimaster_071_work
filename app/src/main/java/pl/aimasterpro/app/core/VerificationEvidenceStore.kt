package pl.aimasterpro.app.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Minimal persistence seam; tests use an in-memory backend, Android uses synchronous commit(). */
interface VerificationEvidenceBackend {
    fun read(): String?
    fun write(value: String): Boolean
    /** Serializes a complete ledger read-modify-write, not only individual backend calls. */
    fun <T> withTransaction(operation: () -> T): T = synchronized(this) { operation() }
}

/** Bounded, versioned local ledger. Corrupt data is never silently overwritten. */
class VerificationEvidenceStore(private val backend: VerificationEvidenceBackend) {
    data class LoadResult(
        val entries: List<VerificationEvidence>,
        val valid: Boolean,
        val errorCode: String = ""
    )

    fun load(): LoadResult = synchronized(PROCESS_MONITOR) { backend.withTransaction { decode() } }

    fun forTask(taskId: String): LoadResult = synchronized(PROCESS_MONITOR) {
        backend.withTransaction {
            if (taskId.isBlank()) return@withTransaction LoadResult(emptyList(), false, "TASK_ID_EMPTY")
            val result = decode()
            if (result.valid) result.copy(entries = result.entries.filter { it.taskId == taskId }) else result
        }
    }

    /** Exact scope lookup; legacy/unscoped evidence is intentionally excluded. */
    fun forScope(taskId: String, chatId: String, snapshotId: String): LoadResult = synchronized(PROCESS_MONITOR) {
        backend.withTransaction {
            if (taskId.isBlank() || chatId.isBlank() || snapshotId.isBlank()) {
                return@withTransaction LoadResult(emptyList(), false, "SCOPE_ID_EMPTY")
            }
            val result = decode()
            if (result.valid) result.copy(entries = result.entries.filter {
                it.taskId == taskId && it.chatId == chatId && it.snapshotId == snapshotId
            }) else result
        }
    }

    /** Idempotent by evidence id. A conflicting duplicate id is rejected, not overwritten. */
    fun appendAll(evidence: List<VerificationEvidence>): Boolean = synchronized(PROCESS_MONITOR) {
        backend.withTransaction {
        if (evidence.isEmpty()) return@withTransaction true
        if (evidence.size > MAX_BATCH_RECORDS) return@withTransaction false
        val current = decode()
        if (!current.valid) return@withTransaction false
        val byId = LinkedHashMap<String, VerificationEvidence>()
        current.entries.forEach { byId[it.id] = it }
        evidence.forEach { item ->
            if (!isWithinBounds(item)) return@withTransaction false
            val previous = byId[item.id]
            if (previous != null && previous != item) return@withTransaction false
            byId[item.id] = item
        }
        val bounded = byId.values.sortedWith(
            compareByDescending<VerificationEvidence> { it.observedAtEpochMs }.thenBy { it.id }
        ).take(MAX_RECORDS)
        writeEncoded(bounded)
        }
    }

    fun removeTask(taskId: String): Boolean = synchronized(PROCESS_MONITOR) {
        backend.withTransaction {
            if (taskId.isBlank()) return@withTransaction false
            val current = decode()
            if (!current.valid) return@withTransaction false
            writeEncoded(current.entries.filterNot { it.taskId == taskId })
        }
    }

    fun clear(): Boolean = synchronized(PROCESS_MONITOR) {
        backend.withTransaction { writeEncoded(emptyList()) }
    }

    private fun decode(): LoadResult {
        return try {
        val raw = backend.read() ?: return LoadResult(emptyList(), true)
        if (raw.isEmpty()) return LoadResult(emptyList(), false, "EMPTY_LEDGER")
        if (raw.length > MAX_ENCODED_CHARS) return LoadResult(emptyList(), false, "LEDGER_TOO_LARGE")
        val lines = raw.lineSequence().filter(String::isNotEmpty).toList()
        if (lines.isEmpty()) return LoadResult(emptyList(), false, "EMPTY_LEDGER")
        val format = lines.first()
        if (format != HEADER && format != HEADER_V1) return LoadResult(emptyList(), false, "UNKNOWN_FORMAT")
        val expectedFields = if (format == HEADER_V1) FIELD_COUNT_V1 else FIELD_COUNT_V2
        if (lines.size - 1 > MAX_RECORDS) return LoadResult(emptyList(), false, "TOO_MANY_RECORDS")
        val decoded = ArrayList<VerificationEvidence>(lines.size - 1)
        val ids = HashSet<String>()
        lines.drop(1).forEach { line ->
            val fields = line.split('.')
            if (fields.size != expectedFields) return LoadResult(emptyList(), false, "MALFORMED_RECORD")
            val values = fields.map(::decodeField)
            val item = VerificationEvidence(
                id = values[0], taskId = values[1],
                kind = VerificationEvidenceKind.valueOf(values[2]),
                status = VerificationEvidenceStatus.valueOf(values[3]),
                origin = VerificationEvidenceOrigin.valueOf(values[4]),
                summary = values[5], observedAtEpochMs = values[6].toLong(), executionId = values[7],
                chatId = if (values.size >= FIELD_COUNT_V2) values[8] else "",
                snapshotId = if (values.size >= FIELD_COUNT_V2) values[9] else ""
            )
            if (!isWithinBounds(item) || !ids.add(item.id)) {
                return LoadResult(emptyList(), false, "INVALID_OR_DUPLICATE_RECORD")
            }
            decoded += item
        }
        LoadResult(decoded, true)
        } catch (_: Exception) {
            LoadResult(emptyList(), false, "DECODE_FAILED")
        }
    }

    private fun writeEncoded(entries: List<VerificationEvidence>): Boolean {
        return try {
        if (entries.size > MAX_RECORDS || entries.any { !isWithinBounds(it) }) return false
        val encoded = buildString {
            append(HEADER)
            entries.forEach { item ->
                append('\n')
                append(listOf(
                    item.id, item.taskId, item.kind.name, item.status.name, item.origin.name,
                    item.summary, item.observedAtEpochMs.toString(), item.executionId, item.chatId, item.snapshotId
                ).joinToString(".", transform = ::encodeField))
            }
        }
        if (encoded.length > MAX_ENCODED_CHARS) return false
        backend.write(encoded)
        } catch (_: Exception) {
            false
        }
    }

    private fun isWithinBounds(item: VerificationEvidence): Boolean =
        item.id.length <= MAX_ID_CHARS && item.taskId.length <= MAX_TASK_ID_CHARS &&
            item.summary.length <= MAX_SUMMARY_CHARS && item.executionId.length <= MAX_EXECUTION_ID_CHARS &&
            item.chatId.length <= MAX_CHAT_ID_CHARS && item.snapshotId.length <= MAX_SNAPSHOT_ID_CHARS

    private fun encodeField(value: String): String = if (value.isEmpty()) EMPTY_FIELD else
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String = if (value == EMPTY_FIELD) "" else {
        val bytes = Base64.getUrlDecoder().decode(value)
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }

    private companion object {
        // All store instances in this process serialize read-modify-write operations.
        // This is deliberately process-local; it is not a multi-process file lock.
        val PROCESS_MONITOR = Any()
        const val HEADER_V1 = "AIMASTER_VERIFICATION_EVIDENCE_V1"
        const val HEADER = "AIMASTER_VERIFICATION_EVIDENCE_V2"
        const val FIELD_COUNT_V1 = 8
        const val FIELD_COUNT_V2 = 10
        const val EMPTY_FIELD = "~"
        const val MAX_RECORDS = 800
        const val MAX_BATCH_RECORDS = 2_000
        const val MAX_ENCODED_CHARS = 1_000_000
        const val MAX_ID_CHARS = 100
        const val MAX_TASK_ID_CHARS = 160
        const val MAX_SUMMARY_CHARS = 500
        const val MAX_EXECUTION_ID_CHARS = 160
        const val MAX_CHAT_ID_CHARS = 160
        const val MAX_SNAPSHOT_ID_CHARS = 160
    }
}
