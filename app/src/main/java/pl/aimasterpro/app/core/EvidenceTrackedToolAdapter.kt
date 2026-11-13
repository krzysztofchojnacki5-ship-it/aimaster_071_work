package pl.aimasterpro.app.core

/**
 * Host-side boundary for a real application operation. It persists NOT_RUN before the
 * operation starts and records whether the host operation returned or threw. A PASS here
 * means only that the adapter completed and returned a result; domain/test outcomes must
 * remain in their own typed evidence records.
 */
class EvidenceTrackedToolAdapter(private val registry: ToolExecutionRegistry) {
    data class Result<T>(
        val value: T?,
        val failure: Throwable?,
        val completion: ToolExecutionRegistry.CompletionResult
    ) {
        val operationReturned: Boolean get() = failure == null && value != null
        val executionId: String get() = completion.evidence?.executionId.orEmpty()
    }

    fun <T : Any> execute(
        taskId: String,
        chatId: String,
        snapshotId: String,
        kind: VerificationEvidenceKind,
        toolName: String,
        operation: () -> T
    ): Result<T> {
        val handle = registry.begin(taskId, chatId, snapshotId, kind, toolName)
        return try {
            val value = operation()
            val completion = registry.complete(
                handle,
                VerificationEvidenceStatus.PASS,
                "Operacja hosta zakończyła się i zwróciła wynik; PASS dotyczy wykonania adaptera, nie wyniku testów projektu."
            )
            Result(value, null, completion)
        } catch (cancelled: InterruptedException) {
            Thread.currentThread().interrupt()
            val persisted = runCatching { registry.abandon(handle, "przerwano wątkiem przed potwierdzeniem wyniku") }.getOrDefault(false)
            Result(null, cancelled, ToolExecutionRegistry.CompletionResult(
                if (persisted) ToolExecutionRegistry.CompletionStatus.PERSISTED
                else ToolExecutionRegistry.CompletionStatus.LEDGER_WRITE_FAILED
            ))
        } catch (cancelled: java.util.concurrent.CancellationException) {
            val persisted = runCatching { registry.abandon(handle, "operacja anulowana przed potwierdzeniem wyniku") }.getOrDefault(false)
            Result(null, cancelled, ToolExecutionRegistry.CompletionResult(
                if (persisted) ToolExecutionRegistry.CompletionStatus.PERSISTED
                else ToolExecutionRegistry.CompletionStatus.LEDGER_WRITE_FAILED
            ))
        } catch (failure: Exception) {
            val completion = runCatching {
                registry.complete(
                    handle,
                    VerificationEvidenceStatus.FAIL,
                    "Operacja hosta przerwana wyjątkiem: ${failure.javaClass.simpleName}: ${failure.message.orEmpty().take(240)}"
                )
            }.getOrElse {
                ToolExecutionRegistry.CompletionResult(ToolExecutionRegistry.CompletionStatus.LEDGER_WRITE_FAILED)
            }
            Result(null, failure, completion)
        }
    }
}
