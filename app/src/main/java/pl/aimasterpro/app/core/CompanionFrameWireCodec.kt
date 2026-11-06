package pl.aimasterpro.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Bounded, versioned binary serialization for authenticated companion frames. No I/O or sockets. */
object CompanionFrameWireCodec {
    private const val MAGIC = 0x41494D46 // AIMF
    private const val WIRE_FORMAT_VERSION = 1
    const val MAX_WIRE_BYTES = 1_400_000
    private const val MAX_SESSION_BYTES = 64
    private const val MAX_DIRECTION_BYTES = 32
    private const val MAX_MESSAGE_TYPE_BYTES = 32
    private const val MAX_HEX_BYTES = 64
    private const val MAX_BASE64_BYTES = 1_398_102 // ceil(1 MiB * 4 / 3), without padding

    data class DecodeResult(val accepted: Boolean, val code: String, val frame: CompanionFrameProtocol.Frame? = null)

    fun encode(frame: CompanionFrameProtocol.Frame): ByteArray {
        validateFrameShape(frame)?.let { throw IllegalArgumentException(it) }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(WIRE_FORMAT_VERSION)
            out.writeInt(frame.protocolVersion)
            writeString(out, frame.sessionId, MAX_SESSION_BYTES)
            writeString(out, frame.direction.name, MAX_DIRECTION_BYTES)
            out.writeLong(frame.sequence)
            writeString(out, frame.messageType.name, MAX_MESSAGE_TYPE_BYTES)
            out.writeInt(frame.payloadBytes)
            writeString(out, frame.payloadBase64, MAX_BASE64_BYTES)
            writeString(out, frame.payloadSha256, MAX_HEX_BYTES)
            writeString(out, frame.mac, MAX_HEX_BYTES)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_WIRE_BYTES) { "Ramka przekracza limit transportowy." }
        }
    }

    /** Rejects oversized input before allocation and rejects malformed UTF-8, unknown enums and trailing bytes. */
    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > MAX_WIRE_BYTES) return DecodeResult(false, "WIRE_TOO_LARGE")
        if (bytes.size < 20) return DecodeResult(false, "TRUNCATED")
        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) return DecodeResult(false, "BAD_MAGIC")
            if (input.readInt() != WIRE_FORMAT_VERSION) return DecodeResult(false, "UNSUPPORTED_WIRE_VERSION")
            val protocolVersion = input.readInt()
            val sessionId = readString(input, MAX_SESSION_BYTES)
            val directionName = readString(input, MAX_DIRECTION_BYTES)
            val sequence = input.readLong()
            val messageName = readString(input, MAX_MESSAGE_TYPE_BYTES)
            val payloadBytes = input.readInt()
            if (payloadBytes !in 0..CompanionFrameProtocol.MAX_PAYLOAD_BYTES) return DecodeResult(false, "PAYLOAD_LIMIT")
            val expectedBase64Length = (payloadBytes * 4 + 2) / 3
            val payloadBase64 = readString(input, minOf(MAX_BASE64_BYTES, expectedBase64Length))
            val digest = readString(input, MAX_HEX_BYTES)
            val mac = readString(input, MAX_HEX_BYTES)
            if (input.available() != 0) return DecodeResult(false, "TRAILING_DATA")
            val direction = CompanionFrameProtocol.Direction.values().firstOrNull { it.name == directionName }
                ?: return DecodeResult(false, "UNKNOWN_DIRECTION")
            val messageType = CompanionFrameProtocol.MessageType.values().firstOrNull { it.name == messageName }
                ?: return DecodeResult(false, "UNKNOWN_MESSAGE_TYPE")
            val frame = CompanionFrameProtocol.Frame(protocolVersion, sessionId, direction, sequence,
                messageType, payloadBase64, payloadBytes, digest, mac)
            validateFrameShape(frame)?.let { return DecodeResult(false, it) }
            // Enforce canonical unpadded Base64URL representation, not merely decoder tolerance.
            val decoded = try { Base64.getUrlDecoder().decode(payloadBase64) }
                catch (_: IllegalArgumentException) { return DecodeResult(false, "INVALID_BASE64") }
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != payloadBase64) {
                return DecodeResult(false, "NON_CANONICAL_BASE64")
            }
            DecodeResult(true, "DECODED", frame)
        } catch (_: EOFException) {
            DecodeResult(false, "TRUNCATED")
        } catch (_: IOException) {
            DecodeResult(false, "MALFORMED_WIRE")
        } catch (_: IllegalArgumentException) {
            DecodeResult(false, "MALFORMED_WIRE")
        } catch (_: NegativeArraySizeException) {
            DecodeResult(false, "MALFORMED_WIRE")
        }
    }

    private fun validateFrameShape(frame: CompanionFrameProtocol.Frame): String? {
        if (frame.protocolVersion != CompanionFrameProtocol.PROTOCOL_VERSION) return "UNSUPPORTED_PROTOCOL_VERSION"
        if (!frame.sessionId.matches(Regex("[A-Za-z0-9_-]{20,64}"))) return "INVALID_SESSION"
        if (frame.sequence <= 0L) return "INVALID_SEQUENCE"
        if (frame.payloadBytes !in 0..CompanionFrameProtocol.MAX_PAYLOAD_BYTES) return "PAYLOAD_LIMIT"
        val expectedLength = (frame.payloadBytes * 4 + 2) / 3
        if (frame.payloadBase64.length != expectedLength || frame.payloadBase64.length > MAX_BASE64_BYTES) return "LENGTH_MISMATCH"
        if (!frame.payloadBase64.matches(Regex("[A-Za-z0-9_-]*"))) return "INVALID_BASE64"
        if (!frame.payloadSha256.matches(Regex("[a-f0-9]{64}"))) return "INVALID_DIGEST"
        if (!frame.mac.matches(Regex("[a-f0-9]{64}"))) return "INVALID_MAC"
        return null
    }

    private fun writeString(out: DataOutputStream, value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) { "Pole tekstowe przekracza limit." }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream, maxBytes: Int): String {
        val length = input.readInt()
        if (length < 0 || length > maxBytes || length > input.available()) throw IOException("Invalid field length")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

}
