package pl.aimasterpro.app.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.IdentityHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Process-relative monotonic clock: pairing expiry must not move when the device wall clock changes. */
private val pairingClockOriginNanos = System.nanoTime()
private fun pairingMonotonicNowMs(): Long = (System.nanoTime() - pairingClockOriginNanos) / 1_000_000L + 1L

/** Opaque in-process handle. Implementations supplied by callers are never trusted as verified proof. */
interface CompanionPairingReceipt

/** Immutable identity metadata released only when the authority consumes an issued receipt. */
data class VerifiedCompanionPairing internal constructor(
    val sessionId: String,
    val clientId: String,
    val companionId: String,
    val nonce: String,
    val verifiedAtMonotonicMs: Long
)

/** Challenge-response contract for a future local companion transport. No sockets or secrets are persisted here. */
class CompanionPairingAuthority internal constructor(
    private val random: SecureRandom,
    private val ttlMs: Long,
    private val clockMs: () -> Long
) {
    /** Production constructor: callers cannot override the monotonic expiry clock per operation. */
    constructor(
        random: SecureRandom = SecureRandom(),
        ttlMs: Long = DEFAULT_TTL_MS
    ) : this(random, ttlMs, ::pairingMonotonicNowMs)
    data class Challenge(val protocolVersion: Int, val sessionId: String, val clientId: String, val nonce: String, val expiresAt: Long, val authenticator: String)
    data class Proof(val protocolVersion: Int, val sessionId: String, val clientId: String, val companionId: String, val nonce: String, val expiresAt: Long, val challengeAuthenticator: String, val mac: String)
    /** `accepted` is diagnostic only. A session owner must consume [receipt] through this authority. */
    data class Verification(
        val accepted: Boolean,
        val code: String,
        val receipt: CompanionPairingReceipt? = null
    )

    private data class IssuedReceipt(
        val sessionId: String,
        val clientId: String,
        val companionId: String,
        val nonce: String,
        val verifiedAtMonotonicMs: Long
    )

    private val pending = LinkedHashMap<String, Challenge>()
    // Identity semantics prevent a caller-created object (or a copied Verification(true, ...)) from
    // becoming proof. Bounded to avoid retaining unclaimed receipts indefinitely.
    private val issuedReceipts = IdentityHashMap<CompanionPairingReceipt, IssuedReceipt>()

    init { require(ttlMs in 5_000L..MAX_TTL_MS) }

    @Synchronized
    fun begin(clientId: String, pairingSecret: ByteArray): Challenge {
        requireValidSecret(pairingSecret)
        require(safeId(clientId)) { "Nieprawidłowy identyfikator klienta." }
        val now = clockMs()
        require(now > 0L) { "Nieprawidłowy czas monotoniczny." }
        prune(now)
        require(pending.size < MAX_PENDING) { "Limit oczekujących parowań został osiągnięty." }
        val unsigned = Challenge(PROTOCOL_VERSION, randomToken(16), clientId, randomToken(32), Math.addExact(now, ttlMs), "")
        val challenge = unsigned.copy(authenticator = hmac(pairingSecret, canonicalChallenge(unsigned)))
        pending[challenge.sessionId] = challenge
        return challenge
    }

    /** Companion-side helper. Pairing secret must be provisioned out-of-band and contain >= 128 bits. */
    fun createProof(challenge: Challenge, companionId: String, pairingSecret: ByteArray): Proof {
        requireValidSecret(pairingSecret)
        require(challenge.protocolVersion == PROTOCOL_VERSION && safeId(challenge.clientId) && safeId(challenge.sessionId) &&
            validToken(challenge.nonce) && challenge.expiresAt > 0L && challenge.authenticator.matches(Regex("[a-f0-9]{64}")) && safeId(companionId)) { "Nieprawidłowe dane wyzwania." }
        val expectedChallengeMac = hmac(pairingSecret, canonicalChallenge(challenge.copy(authenticator = "")))
        require(MessageDigest.isEqual(expectedChallengeMac.toByteArray(StandardCharsets.US_ASCII), challenge.authenticator.toByteArray(StandardCharsets.US_ASCII))) {
            "Wyzwanie nie zostało uwierzytelnione przez klienta."
        }
        val unsigned = Proof(PROTOCOL_VERSION, challenge.sessionId, challenge.clientId, companionId,
            challenge.nonce, challenge.expiresAt, challenge.authenticator, "")
        return unsigned.copy(mac = hmac(pairingSecret, canonical(unsigned)))
    }

    @Synchronized
    fun verify(proof: Proof, pairingSecret: ByteArray): Verification {
        requireValidSecret(pairingSecret)
        val now = clockMs()
        require(now > 0L) { "Nieprawidłowy czas monotoniczny." }
        if (proof.protocolVersion != PROTOCOL_VERSION || !safeId(proof.sessionId) || !safeId(proof.clientId) ||
            !safeId(proof.companionId) || !validToken(proof.nonce) || !proof.challengeAuthenticator.matches(Regex("[a-f0-9]{64}")) || !proof.mac.matches(Regex("[a-f0-9]{64}"))) {
            return Verification(false, "MALFORMED")
        }
        val challenge = pending[proof.sessionId] ?: return Verification(false, "REPLAY_OR_UNKNOWN_SESSION")
        if (now >= challenge.expiresAt) {
            prune(now)
            return Verification(false, "EXPIRED")
        }
        prune(now)
        if (proof.expiresAt != challenge.expiresAt) return Verification(false, "EXPIRED")
        if (proof.clientId != challenge.clientId || proof.nonce != challenge.nonce ||
            !constantEquals(proof.challengeAuthenticator, challenge.authenticator)) {
            return Verification(false, "CHALLENGE_MISMATCH")
        }
        val expected = hmac(pairingSecret, canonical(proof.copy(mac = "")))
        val valid = constantEquals(expected, proof.mac)
        if (!valid) return Verification(false, "AUTH_FAILED")
        if (issuedReceipts.size >= MAX_ISSUED_RECEIPTS) return Verification(false, "RECEIPT_CAPACITY")

        // Only this authority can issue a receipt, and only its exact object identity can be consumed.
        // A public boolean or caller-implemented marker interface is never sufficient evidence.
        val receipt = object : CompanionPairingReceipt {}
        issuedReceipts[receipt] = IssuedReceipt(
            challenge.sessionId, challenge.clientId, proof.companionId, challenge.nonce, now
        )
        // Consume only after a fully authenticated proof and successful bounded receipt issuance.
        pending.remove(proof.sessionId)
        return Verification(true, "PAIRED", receipt)
    }

    /**
     * Converts a receipt issued by this exact authority into session identity metadata exactly once.
     * Returns null for forged, foreign, already-consumed, or otherwise unknown receipts.
     * This is an in-process API capability, not a substitute for TLS peer verification or a process boundary.
     */
    @Synchronized
    fun consumeVerifiedPairing(receipt: CompanionPairingReceipt): VerifiedCompanionPairing? {
        val issued = issuedReceipts.remove(receipt) ?: return null
        return VerifiedCompanionPairing(
            issued.sessionId, issued.clientId, issued.companionId, issued.nonce, issued.verifiedAtMonotonicMs
        )
    }

    @Synchronized
    fun pendingCount(): Int {
        val now = clockMs()
        require(now > 0L) { "Nieprawidłowy czas monotoniczny." }
        prune(now)
        return pending.size
    }

    private fun prune(now: Long) {
        val expired = pending.values.filter { now >= it.expiresAt }.map { it.sessionId }
        expired.forEach { pending.remove(it) }
    }

    private fun constantEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(StandardCharsets.US_ASCII), b.toByteArray(StandardCharsets.US_ASCII))

    private fun randomToken(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))
    private fun safeId(value: String) = value.matches(Regex("[A-Za-z0-9._-]{1,80}"))
    private fun validToken(value: String) = value.matches(Regex("[A-Za-z0-9_-]{20,64}"))
    private fun requireValidSecret(secret: ByteArray) { require(secret.size in 16..64) { "Sekret parowania musi mieć 16–64 bajty." } }

    private fun canonicalChallenge(challenge: Challenge): String = listOf(challenge.protocolVersion.toString(), challenge.sessionId,
        challenge.clientId, challenge.nonce, challenge.expiresAt.toString()).joinToString("\n")

    private fun canonical(proof: Proof): String = listOf(proof.protocolVersion.toString(), proof.sessionId, proof.clientId,
        proof.companionId, proof.nonce, proof.expiresAt.toString(), proof.challengeAuthenticator).joinToString("\n")

    private fun hmac(secret: ByteArray, message: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_TTL_MS = 60_000L
        const val MAX_TTL_MS = 5 * 60_000L
        const val MAX_PENDING = 32
        const val MAX_ISSUED_RECEIPTS = 32
    }
}
