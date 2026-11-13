package pl.aimasterpro.app.core

/** Immutable trust state for one authenticated peer. At most two pins are accepted during rotation. */
data class CompanionPeerPinSet(
    val current: CompanionSpkiSha256Pin,
    val next: CompanionSpkiSha256Pin? = null
) {
    fun accepts(candidate: CompanionSpkiSha256Pin): Boolean =
        candidate == current || candidate == next

    /** Stages a future key without dropping the currently trusted key. */
    fun stage(candidate: CompanionSpkiSha256Pin): CompanionPeerPinSet {
        require(candidate != current) { "Nowy pin musi różnić się od bieżącego." }
        require(next == null || next == candidate) {
            "Istnieje już inny oczekujący pin; anuluj go jawnie przed zastąpieniem."
        }
        return copy(next = candidate)
    }

    /** Cancels a staged rotation without changing the currently trusted key. */
    fun cancelStaged(): CompanionPeerPinSet = copy(next = null)

    /** Promotes only the explicitly staged key; callers must authenticate the rotation out of band. */
    fun promoteNext(): CompanionPeerPinSet {
        val staged = next ?: throw IllegalStateException("Brak oczekującego pinu do rotacji.")
        return CompanionPeerPinSet(current = staged)
    }
}
