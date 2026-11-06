package pl.aimasterpro.app.core

import org.junit.Test

class CompanionFrameWireCodecTest {
    private val key = ByteArray(32) { (it * 7 + 11).toByte() }
    private val session = "abcdefghijklmnopqrstuvwx"

    @Test fun roundTripsFrameAndReceiverAuthenticatesDecodedFrame() {
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION,
            1, CompanionFrameProtocol.MessageType.BUNDLE_CHUNK, "bezpieczny transfer później".toByteArray(), key)
        val wire = CompanionFrameWireCodec.encode(frame)
        val decoded = CompanionFrameWireCodec.decode(wire)
        check(decoded.accepted)
        check(frame == decoded.frame)
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        check(receiver.verify(requireNotNull(decoded.frame)).accepted)
    }

    @Test fun rejectsOversizedTruncatedAndTrailingInput() {
        check("WIRE_TOO_LARGE" == CompanionFrameWireCodec.decode(ByteArray(CompanionFrameWireCodec.MAX_WIRE_BYTES + 1)).code)
        check("TRUNCATED" == CompanionFrameWireCodec.decode(byteArrayOf(1, 2, 3)).code)
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION,
            1, CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key)
        val wire = CompanionFrameWireCodec.encode(frame)
        check("TRAILING_DATA" == CompanionFrameWireCodec.decode(wire + byteArrayOf(0)).code)
    }

    @Test fun supportsMaximumAllowedPayloadWithinWireLimit() {
        val payload = ByteArray(CompanionFrameProtocol.MAX_PAYLOAD_BYTES) { (it * 31).toByte() }
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION,
            1, CompanionFrameProtocol.MessageType.BUNDLE_CHUNK, payload, key)
        val wire = CompanionFrameWireCodec.encode(frame)
        check(wire.size <= CompanionFrameWireCodec.MAX_WIRE_BYTES)
        val decoded = CompanionFrameWireCodec.decode(wire)
        check(decoded.accepted)
        check(frame == decoded.frame)
    }

    @Test fun rejectsUnknownMessageTypeAndBadMagic() {
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION,
            1, CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key)
        val wire = CompanionFrameWireCodec.encode(frame)
        val badMagic = wire.copyOf().also { it[0] = 0 }
        check("BAD_MAGIC" == CompanionFrameWireCodec.decode(badMagic).code)
        // Message type is a length-prefixed UTF-8 field after magic/version/protocol/session/direction/sequence.
        val unknown = wire.copyOf()
        val marker = "PING".toByteArray()
        val index = unknown.indices.firstOrNull { i -> i + marker.size <= unknown.size && marker.indices.all { unknown[i + it] == marker[it] } }
            ?: error("message type not found")
        "NOPE".toByteArray().copyInto(unknown, index)
        check("UNKNOWN_MESSAGE_TYPE" == CompanionFrameWireCodec.decode(unknown).code)
    }

    @Test fun rejectsNonCanonicalBase64AndLeavesAuthenticationToReceiver() {
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION,
            1, CompanionFrameProtocol.MessageType.PING, byteArrayOf(1), key)
        val wire = CompanionFrameWireCodec.encode(frame)
        val decoded = CompanionFrameWireCodec.decode(wire)
        check(decoded.accepted)
        // Java's Base64 decoder tolerates non-zero unused pad bits; the wire codec must reject that alias.
        val nonCanonical = CompanionFrameWireCodec.encode(frame.copy(payloadBase64 = "AR"))
        check("NON_CANONICAL_BASE64" == CompanionFrameWireCodec.decode(nonCanonical).code)
        // Frame-level receiver remains the final authentication gate; codec never marks a frame authenticated.
        val altered = decoded.frame!!.copy(mac = "0".repeat(64))
        val alteredWire = CompanionFrameWireCodec.encode(altered)
        val alteredDecoded = CompanionFrameWireCodec.decode(alteredWire)
        check(alteredDecoded.accepted)
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        check("AUTH_FAILED" == receiver.verify(alteredDecoded.frame!!).code)
    }
}
