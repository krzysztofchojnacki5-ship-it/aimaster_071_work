package pl.aimasterpro.app.core

import java.security.PublicKey
import java.security.cert.X509Certificate

/**
 * Pure orchestration of one TLS leaf issuance (docs/COMPANION_TLS_CERTIFICATE_LIFECYCLE_PL.md,
 * section 4). Platform key storage and the authenticated CA channel are injected, so this flow is
 * testable on the JVM without Android or a network.
 *
 * Fail-closed rules:
 * - an existing alias is never reused or overwritten;
 * - the CA response is accepted only after CompanionTlsLeafPolicy accepts it;
 * - any failure after key generation deletes the new alias;
 * - outcomes carry stable codes and never include private material.
 */
object CompanionTlsIssuanceFlow {

    /** Platform key storage contract. Implementations must keep private keys non-exportable. */
    interface KeyProvider {
        fun aliasExists(alias: String): Boolean

        /** Creates an EC P-256 signing key under [alias] and returns its public key. */
        fun generateEcP256(alias: String): PublicKey

        /** Signs [data] with SHA256withECDSA using the key under [alias]; returns DER signature. */
        fun sign(alias: String, data: ByteArray): ByteArray

        fun delete(alias: String)
    }

    /** Authenticated request/response to the Companion CA over the pairing channel. */
    fun interface CaChannel {
        fun requestLeaf(csrDer: ByteArray): CaResponse
    }

    data class CaResponse(val leaf: X509Certificate, val intermediates: List<X509Certificate>)

    sealed interface Outcome {
        data class Issued(
            val alias: String,
            val leaf: X509Certificate,
            val intermediates: List<X509Certificate>,
            /** SPKI pin of the new leaf; the peer stages it through its own rotation procedure. */
            val leafPin: CompanionSpkiSha256Pin
        ) : Outcome

        data class Rejected(val code: String) : Outcome
    }

    fun aliasFor(version: Int): String {
        require(version in 1..Int.MAX_VALUE) { "Wersja klucza TLS musi być dodatnia." }
        return "ai_master_companion_tls_v$version"
    }

    fun issue(
        version: Int,
        commonName: String,
        dnsNames: Set<String>,
        trustedRoots: Set<X509Certificate>,
        keys: KeyProvider,
        ca: CaChannel,
        nowMillis: Long,
        maxLifetimeMillis: Long = CompanionTlsLeafPolicy.MAX_LEAF_LIFETIME_MILLIS
    ): Outcome {
        val alias = aliasFor(version)
        if (keys.aliasExists(alias)) return Outcome.Rejected("ALIAS_EXISTS")

        var keyCreated = false
        return try {
            val publicKey = keys.generateEcP256(alias)
            keyCreated = true
            val csr = CompanionCsrBuilder.build(commonName, dnsNames.toList(), publicKey) { data ->
                keys.sign(alias, data)
            }
            val response = ca.requestLeaf(csr)
            val verdict = CompanionTlsLeafPolicy.validate(
                leaf = response.leaf,
                intermediates = response.intermediates,
                trustedRoots = trustedRoots,
                expectedPublicKey = publicKey,
                expectedDnsNames = dnsNames,
                nowMillis = nowMillis,
                maxLifetimeMillis = maxLifetimeMillis
            )
            if (verdict.accepted) {
                Outcome.Issued(
                    alias = alias,
                    leaf = response.leaf,
                    intermediates = response.intermediates,
                    leafPin = CompanionSpkiSha256Pin.fromCertificate(response.leaf)
                )
            } else {
                cleanup(keys, alias)
                Outcome.Rejected(verdict.code)
            }
        } catch (_: Exception) {
            if (keyCreated) cleanup(keys, alias)
            Outcome.Rejected("ISSUANCE_FAILED")
        }
    }

    private fun cleanup(keys: KeyProvider, alias: String) {
        try {
            keys.delete(alias)
        } catch (_: Exception) {
            // A failed deletion leaves an unused key in Keystore. It is never referenced because only the
            // alias that passed validation is returned, and the caller must retry deletion explicitly.
        }
    }
}
