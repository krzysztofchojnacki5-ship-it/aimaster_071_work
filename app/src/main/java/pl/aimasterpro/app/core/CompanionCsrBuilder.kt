package pl.aimasterpro.app.core

import java.io.ByteArrayOutputStream
import java.security.PublicKey
import java.security.Signature

/**
 * Builds a PKCS#10 certificate signing request (RFC 2986) for the Companion TLS leaf
 * (docs/COMPANION_TLS_CERTIFICATE_LIFECYCLE_PL.md, section 4 step 2).
 *
 * The DER is encoded by hand because the JDK exposes no public PKCS#10 builder and the project
 * avoids adding dependencies that cannot be verified in the current environment. The signing
 * step is injected so the private key stays inside Android Keystore; the builder verifies the
 * returned signature with the public key before returning, so a mismatched key fails closed.
 *
 * Requested extensions: SAN (exact DNS names), KeyUsage digitalSignature, EKU serverAuth and
 * clientAuth. The CA decides whether to honour them; this builder only asks.
 */
object CompanionCsrBuilder {
    private val CN_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
    private val DNS_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private const val MAX_DNS_NAMES = 8
    private const val MAX_DNS_TOTAL = 253

    /**
     * @param commonName  subject CN, typically the clientId approved during pairing.
     * @param dnsNames    exact DNS names for SAN; lowercased and validated, wildcards rejected.
     * @param publicKey   the Keystore TLS key's public key (EC P-256).
     * @param sign        signs the CertificationRequestInfo bytes with the Keystore private key
     *                    using SHA256withECDSA; must return a DER-encoded ECDSA signature.
     */
    fun build(
        commonName: String,
        dnsNames: List<String>,
        publicKey: PublicKey,
        sign: (ByteArray) -> ByteArray
    ): ByteArray {
        require(CN_PATTERN.matches(commonName)) { "Nieprawidłowy CN dla CSR." }
        val names = normalizeDnsNames(dnsNames)
        require(publicKey.algorithm.equals("EC", ignoreCase = true)) { "CSR wymaga klucza EC." }
        val spki = publicKey.encoded
        require(spki.isNotEmpty() && spki[0] == 0x30.toByte()) { "Nieprawidłowy SubjectPublicKeyInfo." }

        val subject = seq(set(seq(oid(OID_CN), utf8(commonName))))
        val sanValue = seq(*names.map { tlv(0x82, it.toByteArray(Charsets.US_ASCII)) }.toTypedArray())
        val kuValue = tlv(0x03, byteArrayOf(0x07, 0x80.toByte()))  // digitalSignature, 7 unused bits
        val ekuValue = seq(oid(OID_SERVER_AUTH), oid(OID_CLIENT_AUTH))
        val extensions = seq(
            seq(oid(OID_SAN), tlv(0x04, sanValue)),
            seq(oid(OID_KU), tlv(0x01, byteArrayOf(0xff.toByte())), tlv(0x04, kuValue)),
            seq(oid(OID_EKU), tlv(0x04, ekuValue))
        )
        val attributes = tlv(0xA0, seq(oid(OID_EXT_REQ), set(extensions)))
        val info = seq(tlv(0x02, byteArrayOf(0x00)), subject, spki, attributes)

        val signature = sign(info)
        require(signature.isNotEmpty() && signature.size <= MAX_SIGNATURE_BYTES && signature[0] == 0x30.toByte()) {
            "Podpis CSR nie jest DER ECDSA."
        }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(info)
        check(verifier.verify(signature)) { "Podpis CSR nie zgadza się z kluczem publicznym." }

        return seq(info, seq(oid(OID_ECDSA_SHA256)), tlv(0x03, byteArrayOf(0x00) + signature))
    }

    private fun normalizeDnsNames(raw: List<String>): List<String> {
        require(raw.isNotEmpty() && raw.size <= MAX_DNS_NAMES) { "SAN wymaga od 1 do $MAX_DNS_NAMES nazw." }
        val normalized = raw.map { it.trim().lowercase() }
        require(normalized.toSet().size == normalized.size) { "Zduplikowane nazwy SAN." }
        for (name in normalized) {
            require(!name.contains('*')) { "Wildcard niedozwolony w CSR." }
            require(name.length <= MAX_DNS_TOTAL) { "Nazwa SAN jest za długa." }
            require(name.split('.').all { DNS_LABEL.matches(it) }) { "Nieprawidłowa nazwa DNS w SAN." }
        }
        return normalized
    }

    private const val MAX_SIGNATURE_BYTES = 80
    private const val OID_CN = "2.5.4.3"
    private const val OID_EXT_REQ = "1.2.840.113549.1.9.14"
    private const val OID_SAN = "2.5.29.17"
    private const val OID_KU = "2.5.29.15"
    private const val OID_EKU = "2.5.29.37"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val OID_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"
    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"

    internal fun derLength(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bytes = ArrayList<Byte>()
        var v = n
        while (v > 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    internal fun tlv(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(*parts))
    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(*parts))
    private fun utf8(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    internal fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toInt() }
        val out = ByteArrayOutputStream()
        out.write(40 * parts[0] + parts[1])
        for (p in parts.drop(2)) {
            val chunk = ArrayList<Int>()
            var v = p
            chunk.add(v and 0x7F)
            v = v ushr 7
            while (v > 0) {
                chunk.add(0, 0x80 or (v and 0x7F))
                v = v ushr 7
            }
            chunk.forEach { out.write(it) }
        }
        return tlv(0x06, out.toByteArray())
    }
}
