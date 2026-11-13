package pl.aimasterpro.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature

class CompanionIdentityChallengeTest {
    @Test
    fun verifiesDomainSeparatedChallengeProof() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val challenge = ByteArray(32) { (it * 7).toByte() }
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(CompanionIdentityChallenge.message(challenge))
            sign()
        }

        assertTrue(CompanionIdentityChallenge.verify(pair.public, challenge, signature))
        assertFalse(CompanionIdentityChallenge.verify(pair.public, challenge + byteArrayOf(1), signature))
        assertFalse(CompanionIdentityChallenge.verify(pair.public, challenge, signature + byteArrayOf(0)))
    }

    @Test
    fun rejectsEmptyAndOversizedChallenges() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertFalse(CompanionIdentityChallenge.verify(pair.public, byteArrayOf(), byteArrayOf(1)))
        assertFalse(CompanionIdentityChallenge.verify(
            pair.public, ByteArray(CompanionIdentityChallenge.MAX_CHALLENGE_BYTES + 1), byteArrayOf(1)
        ))
    }
}
