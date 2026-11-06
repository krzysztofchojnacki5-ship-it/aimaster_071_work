package pl.aimasterpro.app.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticated, bounded message frames for a future companion transport. This class opens no sockets. */
object CompanionFrameProtocol {
    const val PROTOCOL_VERSION = 1
    const val MAX_PAYLOAD_BYTES = 1_048_576
    private val HEX_256 = Regex("[a-f0-9]{64}")
    private val SESSION = Regex("[A-Za-z0-9_-]{20,64}")

    enum class MessageType { PAIR_CONFIRM, BUNDLE_CHUNK, BUNDLE_COMPLETE, EXECUTION_RECEIPT, PING, CLOSE }
    enum class Direction { ANDROID_TO_COMPANION, COMPANION_TO_ANDROID }
    data class Frame(
        val protocolVersion: Int,
        val sessionId: String,
        val direction: Direction,
        val sequence: Long,
        val messageType: MessageType,
        val payloadBase64: String,
        val payloadBytes: Int,
        val payloadSha256: String,
        val mac: String
    )
    data class Verification(val accepted: Boolean, val code: String, val payload: ByteArray? = null)

    /** Domain-separated session key derivation; call only after CompanionPairingAuthority.verify accepted the proof. */
    fun deriveSessionKey(pairingSecret: ByteArray, sessionId: String, clientId: String, companionId: String, nonce: String, direction: Direction): ByteArray {
        require(pairingSecret.size in 16..64) { "Sekret parowania musi mieć 16–64 bajty." }
        require(SESSION.matches(sessionId) && clientId.matches(Regex("[A-Za-z0-9._-]{1,80}")) &&
            companionId.matches(Regex("[A-Za-z0-9._-]{1,80}")) && nonce.matches(Regex("[A-Za-z0-9_-]{20,64}"))) {
            "Nieprawidłowy kontekst wyprowadzenia klucza."
        }
        val context = "AI_MASTER_PRO_COMPANION_SESSION_KEY_V1\n${direction.name}\n$sessionId\n$clientId\n$companionId\n$nonce"
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(pairingSecret, "HmacSHA256"))
            doFinal(context.toByteArray(StandardCharsets.UTF_8))
        }
    }

    /** Creates a frame. Sequence numbers are strictly positive and assigned by the session owner. */
    fun create(sessionId: String, direction: Direction, sequence: Long, messageType: MessageType, payload: ByteArray, sessionKey: ByteArray): Frame {
        requireValidSession(sessionId)
        require(sequence > 0L) { "Numer sekwencyjny musi być dodatni." }
        requireValidKey(sessionKey)
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Ładunek przekracza limit 1 MiB." }
        val digest = sha256(payload)
        val unsigned = Frame(PROTOCOL_VERSION, sessionId, direction, sequence, messageType,
            Base64.getUrlEncoder().withoutPadding().encodeToString(payload), payload.size, digest, "")
        return unsigned.copy(mac = hmac(sessionKey, canonical(unsigned)))
    }

    /** Stateful, synchronized receiver: accepts exactly the next sequence for one bound session. */
    class Receiver(private val sessionId: String, private val expectedDirection: Direction, sessionKey: ByteArray, firstSequence: Long = 1L) {
        private val key = sessionKey.copyOf()
        private var expectedSequence = firstSequence
        private var closed = false

        init {
            requireValidSession(sessionId)
            requireValidKey(key)
            require(firstSequence > 0L)
        }

        @Synchronized
        fun verify(frame: Frame): Verification {
            if (closed) return Verification(false, "SESSION_CLOSED")
            if (frame.protocolVersion != PROTOCOL_VERSION || frame.sessionId != sessionId || frame.direction != expectedDirection ||
                frame.sequence <= 0L || frame.payloadBytes < 0 || frame.payloadBytes > MAX_PAYLOAD_BYTES ||
                !HEX_256.matches(frame.payloadSha256) || !HEX_256.matches(frame.mac)) {
                return Verification(false, "MALFORMED")
            }
            if (frame.sequence != expectedSequence) return Verification(false, "SEQUENCE_MISMATCH")
            val expectedBase64Length = (frame.payloadBytes * 4 + 2) / 3
            if (frame.payloadBase64.length != expectedBase64Length) return Verification(false, "LENGTH_MISMATCH")
            val payload = try { Base64.getUrlDecoder().decode(frame.payloadBase64) }
                catch (_: IllegalArgumentException) { return Verification(false, "INVALID_BASE64") }
            if (payload.size != frame.payloadBytes) return Verification(false, "LENGTH_MISMATCH")
            // Enforce one canonical representation even when callers bypass the wire codec.
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(payload) != frame.payloadBase64) {
                return Verification(false, "NON_CANONICAL_BASE64")
            }
            val digest = sha256(payload)
            if (!constantEquals(digest, frame.payloadSha256)) return Verification(false, "DIGEST_MISMATCH")
            val expectedMac = hmac(key, canonical(frame.copy(mac = "")))
            if (!constantEquals(expectedMac, frame.mac)) return Verification(false, "AUTH_FAILED")
            if (expectedSequence == Long.MAX_VALUE) {
                // The frame is authentic, but no strictly increasing successor can exist.
                // Fail closed rather than leave a receiver in an ambiguous sequence state.
                closeInternal()
                return Verification(false, "SEQUENCE_EXHAUSTED")
            }
            expectedSequence++
            if (frame.messageType == MessageType.CLOSE) closeInternal()
            return Verification(true, "ACCEPTED", payload)
        }

        /** Explicitly invalidates this receiver (e.g. transport timeout or TLS shutdown). */
        @Synchronized fun close() { closeInternal() }

        @Synchronized fun isClosed(): Boolean = closed

        @Synchronized fun nextExpectedSequence(): Long = expectedSequence

        private fun closeInternal() {
            if (closed) return
            closed = true
            key.fill(0)
        }
    }

    private fun canonical(frame: Frame): String = listOf(
        frame.protocolVersion.toString(), frame.sessionId, frame.direction.name, frame.sequence.toString(), frame.messageType.name,
        frame.payloadBytes.toString(), frame.payloadSha256
    ).joinToString("\n")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, message: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(message.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(StandardCharsets.US_ASCII), b.toByteArray(StandardCharsets.US_ASCII))
    private fun requireValidSession(value: String) { require(SESSION.matches(value)) { "Nieprawidłowa sesja." } }
    private fun requireValidKey(value: ByteArray) { require(value.size in 32..64) { "Klucz sesji musi mieć 32–64 bajty." } }
}
