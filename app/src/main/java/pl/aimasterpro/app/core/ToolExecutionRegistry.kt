package pl.aimasterpro.app.core

import java.util.UUID

/**
 * Host-owned lifecycle for tool invocations. The caller receives an opaque handle;
 * execution IDs are generated here and are never accepted from model-authored text.
 * A durable NOT_RUN record is written before the handle is returned. If the process
 * dies before completion, the ledger therefore cannot mistake the invocation for PASS.
 */
class ToolExecutionRegistry(
    private val sink: EvidenceSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executionIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    fun interface EvidenceSink {
        fun append(evidence: List<VerificationEvidence>): Boolean
    }

    class Handle internal constructor(
        internal val ownerToken: String,
        internal val executionId: String
    )

    enum class CompletionStatus { PERSISTED, LEDGER_WRITE_FAILED, INVALID_HANDLE }

    data class CompletionResult(
        val status: CompletionStatus,
        /** Present only after the completed result was durably accepted by the evidence sink. */
        val evidence: VerificationEvidence? = null
    )

    private data class Pending(
        val handle: Handle,
        val taskId: String,
        val chatId: String,
        val snapshotId: String,
        val kind: VerificationEvidenceKind,
        val toolName: String,
        val startedAt: Long
    )

    private val monitor = Any()
    private val ownerToken = UUID.randomUUID().toString()
    private val pending = LinkedHashMap<String, Pending>()

    /**
     * Persist the pending NOT_RUN marker before exposing the handle to the adapter.
     * A failed ledger write prevents the caller from starting the tool.
     */
    fun begin(
        taskId: String,
        chatId: String,
        snapshotId: String,
        kind: VerificationEvidenceKind,
        toolName: String
    ): Handle {
        require(taskId.isNotBlank()) { "Wywołanie narzędzia wymaga taskId." }
        require(chatId.isNotBlank()) { "Wywołanie narzędzia wymaga chatId." }
        require(snapshotId.isNotBlank()) { "Wywołanie narzędzia wymaga snapshotId." }
        require(toolName.matches(TOOL_NAME_PATTERN)) { "Nieprawidłowa nazwa narzędzia." }
        val startedAt = clock().coerceAtLeast(0L)
        val executionId = executionIdFactory()
        require(executionId.isNotBlank() && executionId.length <= MAX_EXECUTION_ID_CHARS) {
            "Host wygenerował nieprawidłowy executionId."
        }
        val handle = Handle(ownerToken, executionId)
        val item = Pending(handle, taskId, chatId, snapshotId, kind, toolName, startedAt)
        synchronized(monitor) {
            check(executionId !in pending) { "Kolizja executionId; wywołanie zostało odrzucone." }
            pending[executionId] = item
        }
        val startedEvidence = VerificationEvidence.notRun(
            taskId = taskId,
            kind = kind,
            summary = "$toolName: wywołanie rozpoczęte; brak jeszcze wyniku.",
            observedAtEpochMs = startedAt,
            chatId = chatId,
            snapshotId = snapshotId,
            executionId = executionId
        )
        val persisted = runCatching { sink.append(listOf(startedEvidence)) }.getOrDefault(false)
        if (!persisted) {
            synchronized(monitor) {
                if (pending[executionId]?.handle === handle) pending.remove(executionId)
            }
            throw IllegalStateException("Nie udało się trwale zapisać rozpoczęcia wywołania; narzędzie nie może wystartować.")
        }
        return handle
    }

    /** Complete exactly once. A failed persistence leaves the earlier NOT_RUN marker intact. */
    fun complete(
        handle: Handle,
        status: VerificationEvidenceStatus,
        summary: String
    ): CompletionResult {
        require(status == VerificationEvidenceStatus.PASS || status == VerificationEvidenceStatus.FAIL) {
            "Zakończenie wywołania wymaga PASS albo FAIL; brak wyniku zgłoś przez abandon()."
        }
        require(summary.isNotBlank()) { "Wynik narzędzia wymaga opisu." }
        val completed = synchronized(monitor) {
            val current = pending[handle.executionId]
            if (handle.ownerToken != ownerToken || current?.handle !== handle) return CompletionResult(CompletionStatus.INVALID_HANDLE)
            pending.remove(handle.executionId)
            VerificationEvidence.toolExecution(
                taskId = current.taskId,
                kind = current.kind,
                status = status,
                summary = "${current.toolName}: ${summary.take(MAX_SUMMARY_CHARS)}",
                executionId = current.handle.executionId,
                observedAtEpochMs = maxOf(clock().coerceAtLeast(0L), current.startedAt + 1L),
                chatId = current.chatId,
                snapshotId = current.snapshotId
            )
        }
        val persisted = runCatching { sink.append(listOf(completed)) }.getOrDefault(false)
        return if (persisted) CompletionResult(CompletionStatus.PERSISTED, completed)
        else CompletionResult(CompletionStatus.LEDGER_WRITE_FAILED)
    }

    /** Close an invocation without claiming success. */
    fun abandon(handle: Handle, reason: String): Boolean {
        require(reason.isNotBlank()) { "Powód przerwania wywołania nie może być pusty." }
        val notRun = synchronized(monitor) {
            val current = pending[handle.executionId]
            if (handle.ownerToken != ownerToken || current?.handle !== handle) return false
            pending.remove(handle.executionId)
            VerificationEvidence.notRun(
                taskId = current.taskId,
                kind = current.kind,
                summary = "${current.toolName}: NOT_RUN — ${reason.take(MAX_SUMMARY_CHARS)}",
                observedAtEpochMs = maxOf(clock().coerceAtLeast(0L), current.startedAt + 1L),
                chatId = current.chatId,
                snapshotId = current.snapshotId,
                executionId = current.handle.executionId
            )
        }
        return runCatching { sink.append(listOf(notRun)) }.getOrDefault(false)
    }

    fun pendingCount(): Int = synchronized(monitor) { pending.size }

    private companion object {
        val TOOL_NAME_PATTERN = Regex("[A-Za-z0-9_.:-]{1,80}")
        const val MAX_EXECUTION_ID_CHARS = 160
        const val MAX_SUMMARY_CHARS = 400
    }
}
