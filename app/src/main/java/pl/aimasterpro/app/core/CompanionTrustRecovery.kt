package pl.aimasterpro.app.core

/**
 * Explicit trust-state evaluation and recovery for Companion (AGENT_HANDOFF point 3).
 *
 * Rules enforced here:
 *  - a new pairing may start only from [State.Unpaired];
 *  - any mismatch between the stored pieces (pins, PSK, TLS chain, TLS key) is [State.RepairRequired];
 *  - recovery requires a typed confirmation, removes trust in a fixed order (pins first), and never
 *    reads old trust back. An interrupted recovery leaves an inconsistent state, so retrying is safe.
 *
 * Pure logic: storage is reached only through [Snapshot] (built by the caller) and [WipeTargets].
 */
object CompanionTrustRecovery {
    const val CONFIRMATION = "PONOWNE PAROWANIE"

    enum class Status { ABSENT, OK, BAD }

    data class Snapshot(
        val pinsReadable: Boolean,
        val pinsPresent: Boolean,
        val psk: Status,
        val chain: Status,
        val tlsKeyPresent: Boolean
    )

    sealed interface State {
        object Unpaired : State
        object PairedTlsPending : State
        object Paired : State
        data class RepairRequired(val reasons: List<String>) : State
    }

    sealed interface WipeResult {
        object Wiped : WipeResult
        data class Refused(val code: String) : WipeResult
        data class Failed(val step: String) : WipeResult
    }

    /** Each step must be idempotent: removing something already removed is not an error. */
    interface WipeTargets {
        fun clearPins()
        fun clearPsk()
        fun clearChain()
        fun deleteTlsKeys()
    }

    fun evaluate(s: Snapshot): State {
        val reasons = mutableListOf<String>()
        if (!s.pinsReadable) reasons += "PINS_UNREADABLE"
        if (s.psk == Status.BAD) reasons += "PSK_INVALID"
        if (s.chain == Status.BAD) reasons += "CHAIN_INVALID"
        if (reasons.isNotEmpty()) return State.RepairRequired(reasons)

        if (s.pinsPresent && s.psk == Status.ABSENT) reasons += "PINS_WITHOUT_PSK"
        if (!s.pinsPresent && s.psk == Status.OK) reasons += "PSK_WITHOUT_PINS"
        if (!s.pinsPresent && s.chain == Status.OK) reasons += "CHAIN_WITHOUT_PINS"
        if (s.chain == Status.OK && !s.tlsKeyPresent) reasons += "CHAIN_WITHOUT_KEY"
        if (s.chain == Status.ABSENT && s.tlsKeyPresent) reasons += "KEY_WITHOUT_CHAIN"
        if (reasons.isNotEmpty()) return State.RepairRequired(reasons)

        return when {
            !s.pinsPresent && s.psk == Status.ABSENT && s.chain == Status.ABSENT && !s.tlsKeyPresent -> State.Unpaired
            s.pinsPresent && s.psk == Status.OK && s.chain == Status.OK -> State.Paired
            s.pinsPresent && s.psk == Status.OK && s.chain == Status.ABSENT -> State.PairedTlsPending
            else -> State.RepairRequired(listOf("INCONSISTENT"))
        }
    }

    fun canStartNewPairing(state: State): Boolean = state == State.Unpaired

    fun recover(confirmation: String, targets: WipeTargets): WipeResult {
        if (confirmation != CONFIRMATION) return WipeResult.Refused("CONFIRMATION_REQUIRED")
        val steps = listOf(
            "PINS" to { targets.clearPins() },
            "PSK" to { targets.clearPsk() },
            "CHAIN" to { targets.clearChain() },
            "TLS_KEYS" to { targets.deleteTlsKeys() }
        )
        for ((name, step) in steps) {
            try {
                step()
            } catch (_: Exception) {
                return WipeResult.Failed(name)
            }
        }
        return WipeResult.Wiped
    }
}
