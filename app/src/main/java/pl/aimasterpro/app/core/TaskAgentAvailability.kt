package pl.aimasterpro.app.core

/**
 * Read-only, deterministic classification of an agent's already-assigned provider profile.
 * This projection does not perform network calls and never treats unknown pricing as free.
 */
object TaskAgentAvailability {
    enum class Status(val label: String, val canRun: Boolean) {
        FREE_READY("DARMOWY I GOTOWY", true),
        UNKNOWN_PRICE_APPROVED("CENA NIEZNANA • ZGODA ZAPISANA", true),
        UNKNOWN_PRICE_UNAPPROVED("CENA NIEZNANA • BRAK ZGODY", false),
        NOT_CONFIGURED("BRAK KONFIGURACJI", false),
        NOT_READY("NIEGOTOWY", false),
        PAID_OR_UNVERIFIED("NIEPOTWIERDZONY JAKO DARMOWY", false)
    }

    data class Snapshot(
        val agentId: String,
        val providerName: String,
        val model: String,
        val status: Status,
        val detail: String
    )

    fun classify(
        agentId: String,
        configured: Boolean,
        ready: Boolean,
        verifiedFree: Boolean,
        unknownPrice: Boolean,
        unknownPriceApproved: Boolean
    ): Status {
        require(agentId.isNotBlank())
        if (!configured) return Status.NOT_CONFIGURED
        if (!ready) return Status.NOT_READY
        if (verifiedFree) return Status.FREE_READY
        if (unknownPrice) {
            return if (unknownPriceApproved) Status.UNKNOWN_PRICE_APPROVED
            else Status.UNKNOWN_PRICE_UNAPPROVED
        }
        return Status.PAID_OR_UNVERIFIED
    }
}
