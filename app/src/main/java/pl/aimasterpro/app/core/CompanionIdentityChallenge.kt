package pl.aimasterpro.app.core

import java.security.PublicKey
import java.security.Signature

/** Domain-separated proof-of-possession challenge for a provisioned Companion identity key. */
object CompanionIdentityChallenge {
    private val domain = "AI_MASTER_COMPANION_IDENTITY_CHALLENGE_V1\u0000".toByteArray(Charsets.UTF_8)
    const val MAX_CHALLENGE_BYTES = 4096

    /** Returns the exact bytes that must be signed; prevents cross-protocol signature reuse. */
    fun message(challenge: ByteArray): ByteArray {
        require(challenge.isNotEmpty() && challenge.size <= MAX_CHALLENGE_BYTES) {
            "Challenge tożsamości Companion musi mieć 1–4096 bajtów."
        }
        return ByteArray(domain.size + challenge.size).also {
            domain.copyInto(it)
            challenge.copyInto(it, domain.size)
        }
    }

    /** Verifies a proof with the public key distributed in the peer's authenticated certificate. */
    fun verify(publicKey: PublicKey, challenge: ByteArray, signatureBytes: ByteArray): Boolean {
        if (signatureBytes.isEmpty() || signatureBytes.size > MAX_SIGNATURE_BYTES) return false
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message(challenge))
                verify(signatureBytes)
            }
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: java.security.GeneralSecurityException) {
            false
        }
    }

    private const val MAX_SIGNATURE_BYTES = 256
}
