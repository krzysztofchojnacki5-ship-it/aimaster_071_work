package pl.aimasterpro.app.core

import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Pure, platform-independent validation of a TLS leaf certificate returned by the Companion CA
 * (see docs/COMPANION_TLS_CERTIFICATE_LIFECYCLE_PL.md, section 4 step 6).
 *
 * No Android APIs, no I/O, no state. Every rejection returns a stable code and never throws for
 * malformed input; callers must treat any non-accepted verdict as fail-closed.
 * This class does not replace platform TLS validation; it is a gate applied to CA output before
 * the certificate is stored.
 */
object CompanionTlsLeafPolicy {
    /** Design limit for leaf TLS certificates issued by the Companion CA. */
    const val MAX_LEAF_LIFETIME_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L

    /** Maximum number of intermediate certificates accepted between leaf and trusted root. */
    const val MAX_INTERMEDIATES: Int = 2

    const val EKU_SERVER_AUTH: String = "1.3.6.1.5.5.7.3.1"
    const val EKU_CLIENT_AUTH: String = "1.3.6.1.5.5.7.3.2"

    private const val SAN_TYPE_DNS = 2
    private const val KEY_USAGE_DIGITAL_SIGNATURE = 0
    private const val KEY_USAGE_CERT_SIGN = 5

    data class Verdict(val accepted: Boolean, val code: String)

    /**
     * @param leaf                the certificate returned by the CA.
     * @param intermediates       intermediates in order from leaf upward; may be empty.
     * @param trustedRoots        anchors pinned for this Companion pairing; must be non-empty.
     * @param expectedPublicKey   public key of the Keystore key that produced the CSR.
     * @param expectedDnsNames    exact DNS names approved during pairing; wildcards are rejected.
     * @param requiredEku         EKU OIDs the leaf must carry.
     * @param maxLifetimeMillis   maximum notAfter - notBefore.
     */
    fun validate(
        leaf: X509Certificate,
        intermediates: List<X509Certificate>,
        trustedRoots: Set<X509Certificate>,
        expectedPublicKey: PublicKey,
        expectedDnsNames: Set<String>,
        nowMillis: Long,
        requiredEku: Set<String> = setOf(EKU_SERVER_AUTH, EKU_CLIENT_AUTH),
        maxLifetimeMillis: Long = MAX_LEAF_LIFETIME_MILLIS
    ): Verdict {
        if (trustedRoots.isEmpty()) return reject("NO_TRUSTED_ROOT")
        if (expectedDnsNames.isEmpty() || expectedDnsNames.any { it.isBlank() || it.contains('*') }) {
            return reject("INVALID_EXPECTATION")
        }
        if (requiredEku.isEmpty()) return reject("INVALID_EXPECTATION")
        if (intermediates.size > MAX_INTERMEDIATES) return reject("CHAIN_TOO_LONG")

        if (leaf.basicConstraints != -1) return reject("LEAF_IS_CA")
        if (leaf.issuerX500Principal == leaf.subjectX500Principal) return reject("SELF_SIGNED_LEAF")
        if (!leaf.publicKey.encoded.contentEquals(expectedPublicKey.encoded)) return reject("KEY_MISMATCH")

        val now = Date(nowMillis)
        try {
            leaf.checkValidity(now)
        } catch (_: java.security.cert.CertificateNotYetValidException) {
            return reject("NOT_YET_VALID")
        } catch (_: java.security.cert.CertificateExpiredException) {
            return reject("EXPIRED")
        } catch (_: GeneralSecurityException) {
            return reject("INVALID_VALIDITY")
        }
        if (leaf.notAfter.time - leaf.notBefore.time > maxLifetimeMillis) return reject("LIFETIME_TOO_LONG")

        val keyUsage = leaf.keyUsage
        if (keyUsage == null || keyUsage.size <= KEY_USAGE_DIGITAL_SIGNATURE || !keyUsage[KEY_USAGE_DIGITAL_SIGNATURE]) {
            return reject("KEY_USAGE_MISSING")
        }

        val eku = leaf.extendedKeyUsage.orEmpty()
        if (!eku.containsAll(requiredEku)) return reject("EKU_MISSING")

        val allSans = leaf.subjectAlternativeNames.orEmpty()
        if (allSans.any { it[0] != SAN_TYPE_DNS }) return reject("UNEXPECTED_SAN_TYPE")
        val leafDns = allSans.map { it[1].toString().lowercase() }.toSet()
        if (leafDns.any { it.contains('*') }) return reject("WILDCARD_SAN")
        if (leafDns != expectedDnsNames.map { it.lowercase() }.toSet()) return reject("SAN_MISMATCH")

        // Chain: leaf -> intermediates -> one trusted root. Each child must be signed by its parent.
        val chain = listOf(leaf) + intermediates
        for (index in chain.indices) {
            val child = chain[index]
            val isLast = index == chain.lastIndex
            val candidates: List<X509Certificate> = if (isLast) trustedRoots.toList() else listOf(chain[index + 1])
            val parent = candidates.firstOrNull { candidate ->
                child.issuerX500Principal == candidate.subjectX500Principal && signedBy(child, candidate)
            } ?: return reject(if (isLast) "UNTRUSTED_ROOT" else "CHAIN_BROKEN")

            if (parent.basicConstraints < 0) return reject("ISSUER_NOT_CA")
            val parentKeyUsage = parent.keyUsage
            if (parentKeyUsage != null && parentKeyUsage.size > KEY_USAGE_CERT_SIGN && !parentKeyUsage[KEY_USAGE_CERT_SIGN]) {
                return reject("ISSUER_CANNOT_SIGN")
            }
            if (!validAt(parent, now)) return reject("ISSUER_NOT_VALID")
        }
        return Verdict(true, "ACCEPTED")
    }

    private fun reject(code: String) = Verdict(false, code)

    private fun signedBy(child: X509Certificate, parent: X509Certificate): Boolean =
        try {
            child.verify(parent.publicKey)
            true
        } catch (_: Exception) {
            false
        }

    private fun validAt(cert: X509Certificate, now: Date): Boolean =
        try {
            cert.checkValidity(now)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
}
