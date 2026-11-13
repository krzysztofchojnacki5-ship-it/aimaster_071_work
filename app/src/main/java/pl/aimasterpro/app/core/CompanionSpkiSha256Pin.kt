package pl.aimasterpro.app.core

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale

/** Immutable SHA-256 pin of the DER-encoded SubjectPublicKeyInfo (SPKI). */
class CompanionSpkiSha256Pin private constructor(private val canonicalHex: String) {
    fun matches(certificate: X509Certificate): Boolean {
        val candidate = MessageDigest.getInstance("SHA-256")
            .digest(certificate.publicKey.encoded)
        return MessageDigest.isEqual(hexToBytes(canonicalHex), candidate)
    }

    override fun equals(other: Any?): Boolean =
        other is CompanionSpkiSha256Pin && MessageDigest.isEqual(
            hexToBytes(canonicalHex), hexToBytes(other.canonicalHex)
        )

    override fun hashCode(): Int = canonicalHex.hashCode()

    override fun toString(): String = canonicalHex

    companion object {
        fun parse(value: String): CompanionSpkiSha256Pin {
            require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Pin SPKI musi być kanonicznym, małymi literami zapisanym SHA-256 (64 znaki hex)."
            }
            return CompanionSpkiSha256Pin(value)
        }

        fun fromCertificate(certificate: X509Certificate): CompanionSpkiSha256Pin {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.publicKey.encoded)
            return CompanionSpkiSha256Pin(digest.joinToString("") { "%02x".format(Locale.ROOT, it) })
        }

        private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
