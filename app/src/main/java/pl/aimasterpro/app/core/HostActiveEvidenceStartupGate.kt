package pl.aimasterpro.app.core

/** Fail-closed gate for selecting host evidence while workspace recovery may still be pending. */
object HostActiveEvidenceStartupGate {
    enum class WorkspaceTransactionState { CLEAR, PENDING, UNKNOWN }

    /** The active-pointer read is lazy: it is never invoked unless the transaction state is CLEAR. */
    fun resolve(
        transactionState: WorkspaceTransactionState,
        readActive: () -> HostActiveChangeSetStore.ReadResult
    ): HostActiveChangeSetStore.ReadResult = when (transactionState) {
        WorkspaceTransactionState.CLEAR -> runCatching { readActive() }.getOrElse {
            HostActiveChangeSetStore.ReadResult(
                HostActiveChangeSetStore.ReadStatus.INVALID,
                reason = "Nie można odczytać aktywnego zakresu dowodów"
            )
        }
        WorkspaceTransactionState.PENDING -> HostActiveChangeSetStore.ReadResult(
            HostActiveChangeSetStore.ReadStatus.INVALID,
            reason = "Transakcja workspace/patch receipt oczekuje na uzgodnienie; zakres dowodów zablokowany"
        )
        WorkspaceTransactionState.UNKNOWN -> HostActiveChangeSetStore.ReadResult(
            HostActiveChangeSetStore.ReadStatus.INVALID,
            reason = "Nie można zweryfikować dziennika transakcji/receipt workspace; zakres dowodów zablokowany"
        )
    }
}
