package pl.aimasterpro.app.core

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompanionFrameProtocolTest {
    private val key = ByteArray(32) { (it * 13 + 5).toByte() }
    private val session = "abcdefghijklmnopqrstuvwx"

    @Test fun acceptsAuthenticatedFrameAndAdvancesSequence() {
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        val payload = "bundle-chunk".toByteArray()
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1, CompanionFrameProtocol.MessageType.BUNDLE_CHUNK, payload, key)
        val result = receiver.verify(frame)
        check(result.accepted)
        check(payload.contentEquals(result.payload))
        check(2L == receiver.nextExpectedSequence())
    }

    @Test fun rejectsReplayOutOfOrderAndWrongSession() {
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1, CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key)
        check(receiver.verify(frame).accepted)
        check("SEQUENCE_MISMATCH" == receiver.verify(frame).code)
        val wrong = CompanionFrameProtocol.create("zyxwvutsrqponmlkjihgfedc", CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 2, CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key)
        check("MALFORMED" == receiver.verify(wrong).code)
    }

    @Test fun rejectsTamperingWithoutConsumingSequence() {
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1, CompanionFrameProtocol.MessageType.BUNDLE_CHUNK, byteArrayOf(1, 2, 3), key)
        check("AUTH_FAILED" == receiver.verify(frame.copy(mac = "0".repeat(64))).code)
        check("DIGEST_MISMATCH" == receiver.verify(frame.copy(payloadSha256 = "0".repeat(64))).code)
        check(receiver.verify(frame).accepted)
    }

    @Test fun rejectsReflectedFrameFromOppositeDirection() {
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.COMPANION_TO_ANDROID, key)
        val reflected = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1, CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key)
        check("MALFORMED" == receiver.verify(reflected).code)
    }

    @Test fun receiverRejectsNonCanonicalBase64EvenWhenWireCodecIsBypassed() {
        val receiver = CompanionFrameProtocol.Receiver(session,
            CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        val frame = CompanionFrameProtocol.create(session,
            CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1,
            CompanionFrameProtocol.MessageType.PING, byteArrayOf(0), key)
        // Java's URL decoder accepts "AB" as the same single byte as canonical "AA";
        // the receiver must not accept alternate encodings at its own trust boundary.
        check("AA" == frame.payloadBase64)
        check("NON_CANONICAL_BASE64" == receiver.verify(frame.copy(payloadBase64 = "AB")).code)
        check(receiver.verify(frame).accepted)
    }

    @Test fun rejectsMalformedLengthAndInvalidBase64() {
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        val frame = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1, CompanionFrameProtocol.MessageType.PING, byteArrayOf(1), key)
        check("LENGTH_MISMATCH" == receiver.verify(frame.copy(payloadBytes = 2)).code)
        check("LENGTH_MISMATCH" == receiver.verify(frame.copy(payloadBase64 = "A".repeat(1_000_000), payloadBytes = 1)).code)
        check("INVALID_BASE64" == receiver.verify(frame.copy(payloadBase64 = "!!", payloadBytes = 1)).code)
        check(receiver.verify(frame).accepted)
    }

    @Test fun authenticatedCloseIsTerminalAndInvalidatesReceiver() {
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        val close = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1,
            CompanionFrameProtocol.MessageType.CLOSE, byteArrayOf(), key)
        check(receiver.verify(close).accepted)
        check(receiver.isClosed())
        val later = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 2,
            CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key)
        check("SESSION_CLOSED" == receiver.verify(later).code)
        check("SESSION_CLOSED" == receiver.verify(close).code)
        receiver.close() // Idempotent.
        check(receiver.isClosed())
    }

    @Test fun explicitCloseAndAuthenticatedSequenceExhaustionAreTerminal() {
        val manuallyClosed = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        manuallyClosed.close()
        val ping = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1,
            CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key)
        check("SESSION_CLOSED" == manuallyClosed.verify(ping).code)

        val exhausted = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key, Long.MAX_VALUE)
        val finalSequence = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, Long.MAX_VALUE,
            CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key)
        check("SEQUENCE_EXHAUSTED" == exhausted.verify(finalSequence).code)
        check(exhausted.isClosed())
        check("SESSION_CLOSED" == exhausted.verify(finalSequence).code)
    }

    @Test fun unauthenticatedCloseCannotTerminateReceiver() {
        val receiver = CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        val close = CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1,
            CompanionFrameProtocol.MessageType.CLOSE, byteArrayOf(), key)
        check("AUTH_FAILED" == receiver.verify(close.copy(mac = "0".repeat(64))).code)
        check(!receiver.isClosed())
        check(receiver.verify(close).accepted)
        check(receiver.isClosed())
    }

    @Test fun derivesSessionKeysWithContextSeparation() {
        val first = CompanionFrameProtocol.deriveSessionKey(key, session, "android-client", "desktop-companion", "A".repeat(32), CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION)
        val same = CompanionFrameProtocol.deriveSessionKey(key, session, "android-client", "desktop-companion", "A".repeat(32), CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION)
        val other = CompanionFrameProtocol.deriveSessionKey(key, session, "android-client", "desktop-companion", "B".repeat(32), CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION)
        check(first.contentEquals(same))
        check(!first.contentEquals(other))
        val reverse = CompanionFrameProtocol.deriveSessionKey(key, session, "android-client", "desktop-companion", "A".repeat(32), CompanionFrameProtocol.Direction.COMPANION_TO_ANDROID)
        check(!first.contentEquals(reverse))
        check(first.size == 32)
    }


    @Test fun receiverOwnsAnIndependentCopyOfItsSessionKey() {
        val callerOwnedKey = key.copyOf()
        val receiver = CompanionFrameProtocol.Receiver(session,
            CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, callerOwnedKey)
        callerOwnedKey.fill(0)
        val frame = CompanionFrameProtocol.create(session,
            CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1,
            CompanionFrameProtocol.MessageType.PING, byteArrayOf(7), key)
        check(receiver.verify(frame).accepted)
    }

    @Test fun concurrentReplayCanBeAcceptedOnlyOnce() {
        val receiver = CompanionFrameProtocol.Receiver(session,
            CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key)
        val frame = CompanionFrameProtocol.create(session,
            CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1,
            CompanionFrameProtocol.MessageType.PING, byteArrayOf(1, 2), key)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<CompanionFrameProtocol.Verification> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    receiver.verify(frame)
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val outcomes = results.map { it.get(5, TimeUnit.SECONDS) }
            check(1 == outcomes.count { it.accepted })
            check(1 == outcomes.count { it.code == "SEQUENCE_MISMATCH" })
            check(2L == receiver.nextExpectedSequence())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun enforcesKeySequenceSessionAndPayloadLimits() {
        check(runCatching { CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, ByteArray(16)) }.isFailure)
        check(runCatching { CompanionFrameProtocol.Receiver(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, key, 0) }.isFailure)
        check(runCatching { CompanionFrameProtocol.create("bad session", CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1, CompanionFrameProtocol.MessageType.PING, byteArrayOf(), key) }.isFailure)
        check(runCatching { CompanionFrameProtocol.create(session, CompanionFrameProtocol.Direction.ANDROID_TO_COMPANION, 1, CompanionFrameProtocol.MessageType.PING, ByteArray(CompanionFrameProtocol.MAX_PAYLOAD_BYTES + 1), key) }.isFailure)
    }
}
