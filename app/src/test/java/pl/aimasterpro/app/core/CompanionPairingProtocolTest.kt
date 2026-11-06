package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class CompanionPairingProtocolTest {
    private val secret = ByteArray(32) { (it * 7 + 3).toByte() }
    private val now = 1_800_000_000_000L

    private class FakeClock(var value: Long = 1_800_000_000_000L) {
        fun read(): Long = value
    }

    private fun authority(clock: FakeClock = FakeClock()): CompanionPairingAuthority =
        CompanionPairingAuthority(java.security.SecureRandom(), CompanionPairingAuthority.DEFAULT_TTL_MS, clock::read)

    @Test fun validChallengeResponsePairsExactlyOnce() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        clock.value = now + 1
        assertTrue(authority.verify(proof, secret).accepted)
        clock.value = now + 2
        assertEquals("REPLAY_OR_UNKNOWN_SESSION", authority.verify(proof, secret).code)
    }

    @Test fun failedAuthenticationDoesNotConsumePendingChallenge() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        clock.value = now + 1
        assertEquals("AUTH_FAILED", authority.verify(proof, ByteArray(32) { 9 }).code)
        assertEquals(1, authority.pendingCount())
        clock.value = now + 2
        assertTrue(authority.verify(proof, secret).accepted)
        clock.value = now + 3
        assertEquals("REPLAY_OR_UNKNOWN_SESSION", authority.verify(proof, secret).code)
    }

    @Test fun alteredChallengeAuthenticatorDoesNotConsumePendingChallenge() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        clock.value = now + 1
        assertEquals("CHALLENGE_MISMATCH", authority.verify(proof.copy(challengeAuthenticator = "0".repeat(64)), secret).code)
        assertEquals(1, authority.pendingCount())
        clock.value = now + 2
        assertTrue(authority.verify(proof, secret).accepted)
    }

    @Test fun rejectsExpiredChallengeAndMismatchedNonce() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val expired = authority.begin("android-client", secret)
        val expiredProof = authority.createProof(expired, "desktop-companion", secret)
        clock.value = expired.expiresAt
        assertEquals("EXPIRED", authority.verify(expiredProof, secret).code)

        clock.value = now + 10
        val fresh = authority.begin("android-client", secret)
        val proof = authority.createProof(fresh, "desktop-companion", secret).copy(nonce = "A".repeat(32))
        clock.value = now + 11
        assertEquals("CHALLENGE_MISMATCH", authority.verify(proof, secret).code)
    }

    @Test fun companionRejectsUnauthenticatedChallengeAndAndroidRejectsAlteredProof() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val challenge = authority.begin("android-client", secret)
        assertTrue(runCatching {
            authority.createProof(challenge.copy(clientId = "attacker"), "desktop-companion", secret)
        }.isFailure)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        clock.value = now + 1
        assertEquals("CHALLENGE_MISMATCH", authority.verify(proof.copy(challengeAuthenticator = "0".repeat(64)), secret).code)
    }

    @Test fun defaultClockCreatesAndVerifiesPairingWithoutWallClockInput() {
        val authority = CompanionPairingAuthority()
        val secret = ByteArray(32) { (it + 7).toByte() }
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        assertTrue(authority.verify(proof, secret).accepted)
    }

    @Test fun rejectsWeakSecretsMalformedIdsAndPendingOverflow() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        assertTrue(runCatching { authority.begin("bad id", secret) }.isFailure)
        assertTrue(runCatching { authority.createProof(authority.begin("client2", secret), "pc", ByteArray(8)) }.isFailure)
        val capacityClock = FakeClock(now)
        val capacityAuthority = authority(capacityClock)
        repeat(CompanionPairingAuthority.MAX_PENDING) { capacityAuthority.begin("client$it", secret) }
        assertTrue(runCatching { capacityAuthority.begin("overflow", secret) }.isFailure)
    }

    @Test fun expiryUsesAuthorityOwnedClockAndCannotBeOverriddenPerCall() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)

        clock.value = challenge.expiresAt - 1
        assertEquals(1, authority.pendingCount())
        clock.value = challenge.expiresAt
        assertEquals("EXPIRED", authority.verify(proof, secret).code)
        assertEquals(0, authority.pendingCount())
    }

    @Test fun pairingReceiptIsAuthorityIssuedAndConsumableExactlyOnce() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)

        clock.value = now + 1
        val result = authority.verify(proof, secret)
        assertTrue(result.accepted)
        assertEquals("PAIRED", result.code)
        val receipt = result.receipt ?: throw AssertionError("Brak receipt po poprawnym proof")
        val identity = authority.consumeVerifiedPairing(receipt)
        assertTrue(identity != null)
        assertEquals(challenge.sessionId, identity!!.sessionId)
        assertEquals("android-client", identity.clientId)
        assertEquals("desktop-companion", identity.companionId)
        assertEquals(now + 1, identity.verifiedAtMonotonicMs)
        assertTrue(authority.consumeVerifiedPairing(receipt) == null)
    }

    @Test fun forgedAcceptedBooleanAndCallerReceiptCannotCreateVerifiedIdentity() {
        val authority = authority()
        val forgedReceipt = object : CompanionPairingReceipt {}
        val forged = CompanionPairingAuthority.Verification(true, "PAIRED", forgedReceipt)

        assertTrue(forged.accepted) // The field is intentionally diagnostic, not an authorization gate.
        assertTrue(authority.consumeVerifiedPairing(forged.receipt!!) == null)
        assertTrue(authority.consumeVerifiedPairing(object : CompanionPairingReceipt {}) == null)
    }

    @Test fun concurrentReceiptConsumptionSucceedsExactlyOnce() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val challenge = authority.begin("android-client", secret)
        val proof = authority.createProof(challenge, "desktop-companion", secret)
        clock.value = now + 1
        val receipt = authority.verify(proof, secret).receipt
            ?: throw AssertionError("Brak receipt po poprawnym proof")

        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map {
                workers.submit<Boolean> {
                    start.await()
                    authority.consumeVerifiedPairing(receipt) != null
                }
            }
            start.countDown()
            val successfulClaims = results.count { it.get() }
            assertEquals(1, successfulClaims)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun unclaimedReceiptsAreBoundedAndCapacityDoesNotConsumeChallenge() {
        val clock = FakeClock(now)
        val authority = authority(clock)
        val issued = mutableListOf<CompanionPairingReceipt>()
        repeat(CompanionPairingAuthority.MAX_ISSUED_RECEIPTS) { index ->
            val challenge = authority.begin("client$index", secret)
            val proof = authority.createProof(challenge, "desktop", secret)
            clock.value++
            val result = authority.verify(proof, secret)
            assertTrue(result.accepted)
            issued += result.receipt!!
        }

        val pendingChallenge = authority.begin("capacity-client", secret)
        val pendingProof = authority.createProof(pendingChallenge, "desktop", secret)
        clock.value++
        assertEquals("RECEIPT_CAPACITY", authority.verify(pendingProof, secret).code)
        assertEquals(1, authority.pendingCount())

        assertTrue(authority.consumeVerifiedPairing(issued.first()) != null)
        val retry = authority.verify(pendingProof, secret)
        assertTrue(retry.accepted)
        assertTrue(retry.receipt != null)
    }

    @Test fun expiredChallengeIsPrunedBeforeCapacityCheck() {
        val clock = FakeClock(now)
        val authority = CompanionPairingAuthority(java.security.SecureRandom(), 5_000L, clock::read)
        repeat(CompanionPairingAuthority.MAX_PENDING) { authority.begin("client$it", secret) }
        clock.value = now + 5_000L
        assertEquals(0, authority.pendingCount())
        authority.begin("replacement", secret)
        assertEquals(1, authority.pendingCount())
    }
}
