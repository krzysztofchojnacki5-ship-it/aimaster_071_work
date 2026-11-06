package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Issuance flow on JVM fixtures (companion-tls/ec, generated with openssl and verified with
 * `openssl verify`). The fake key provider holds the fixture EC key; the fake CA returns fixtures.
 * NOT RUN in this environment.
 */
class CompanionTlsIssuanceFlowTest {
    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("companion-tls/ec/$name")!!.readBytes()

    private fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(resource(name).inputStream()) as X509Certificate

    private fun rsaCert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            javaClass.classLoader!!.getResourceAsStream("companion-tls/$name.pem")!!
        ) as X509Certificate

    private fun leafKey(): PrivateKey {
        val body = String(resource("ec-leaf-key.pem"), Charsets.US_ASCII).lineSequence()
            .filterNot { it.startsWith("-----") }.joinToString("")
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
    }

    private class FakeKeys(
        private val pub: PublicKey,
        private val priv: PrivateKey,
        existing: Set<String> = emptySet()
    ) : CompanionTlsIssuanceFlow.KeyProvider {
        val aliases = existing.toMutableSet()
        val deleted = mutableListOf<String>()
        var generated = 0

        override fun aliasExists(alias: String) = alias in aliases
        override fun generateEcP256(alias: String): PublicKey {
            aliases.add(alias)
            generated++
            return pub
        }
        override fun sign(alias: String, data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(priv)
                update(data)
                sign()
            }
        override fun delete(alias: String) {
            aliases.remove(alias)
            deleted.add(alias)
        }
    }

    private val leaf = cert("ec-leaf.pem")
    private val root = cert("ec-root.pem")
    private val foreignRoot = cert("ec-rogue-root.pem")
    private val names = setOf("companion-test")
    private val withinValidity = leaf.notBefore.time + 86_400_000L

    private fun issueWith(
        keys: FakeKeys,
        ca: CompanionTlsIssuanceFlow.CaChannel,
        trusted: Set<X509Certificate> = setOf(root),
        now: Long = withinValidity,
        version: Int = 1
    ) = CompanionTlsIssuanceFlow.issue(
        version = version,
        commonName = "companion-client",
        dnsNames = names,
        trustedRoots = trusted,
        keys = keys,
        ca = ca,
        nowMillis = now
    )

    @Test fun issuesLeafWhenCaResponseValidates() {
        val keys = FakeKeys(leaf.publicKey, leafKey())
        val outcome = issueWith(keys, CompanionTlsIssuanceFlow.CaChannel { csr ->
            assertEquals(0x30.toByte(), csr[0])
            CompanionTlsIssuanceFlow.CaResponse(leaf, emptyList())
        })
        assertTrue(outcome is CompanionTlsIssuanceFlow.Outcome.Issued)
        outcome as CompanionTlsIssuanceFlow.Outcome.Issued
        assertEquals("ai_master_companion_tls_v1", outcome.alias)
        assertEquals(CompanionSpkiSha256Pin.fromCertificate(leaf), outcome.leafPin)
        assertTrue(keys.deleted.isEmpty())
    }

    @Test fun rejectsLeafFromForeignRootAndDeletesKey() {
        val keys = FakeKeys(leaf.publicKey, leafKey())
        val outcome = issueWith(keys, { CompanionTlsIssuanceFlow.CaResponse(leaf, emptyList()) }, trusted = setOf(foreignRoot))
        assertEquals("UNTRUSTED_ROOT", (outcome as CompanionTlsIssuanceFlow.Outcome.Rejected).code)
        assertEquals(listOf("ai_master_companion_tls_v1"), keys.deleted)
    }

    @Test fun rejectsLeafWithKeyNotGeneratedForThisRequest() {
        val keys = FakeKeys(leaf.publicKey, leafKey())
        val outcome = issueWith(keys, { CompanionTlsIssuanceFlow.CaResponse(rsaCert("server"), emptyList()) })
        assertEquals("KEY_MISMATCH", (outcome as CompanionTlsIssuanceFlow.Outcome.Rejected).code)
        assertEquals(1, keys.deleted.size)
    }

    @Test fun rejectsExpiredLeafAndDeletesKey() {
        val keys = FakeKeys(leaf.publicKey, leafKey())
        val outcome = issueWith(keys, { CompanionTlsIssuanceFlow.CaResponse(leaf, emptyList()) }, now = leaf.notAfter.time + 1L)
        assertEquals("EXPIRED", (outcome as CompanionTlsIssuanceFlow.Outcome.Rejected).code)
        assertEquals(1, keys.deleted.size)
    }

    @Test fun neverOverwritesExistingAlias() {
        val keys = FakeKeys(leaf.publicKey, leafKey(), existing = setOf("ai_master_companion_tls_v1"))
        val outcome = issueWith(keys, { error("CA must not be called") })
        assertEquals("ALIAS_EXISTS", (outcome as CompanionTlsIssuanceFlow.Outcome.Rejected).code)
        assertEquals(0, keys.generated)
        assertTrue(keys.deleted.isEmpty())
    }

    @Test fun caFailureDeletesGeneratedKeyFailClosed() {
        val keys = FakeKeys(leaf.publicKey, leafKey())
        val outcome = issueWith(keys, { throw IOException("channel closed") })
        assertEquals("ISSUANCE_FAILED", (outcome as CompanionTlsIssuanceFlow.Outcome.Rejected).code)
        assertEquals(listOf("ai_master_companion_tls_v1"), keys.deleted)
    }

    @Test fun aliasIsVersionedAndNonCollidingAcrossRotations() {
        assertEquals("ai_master_companion_tls_v2", CompanionTlsIssuanceFlow.aliasFor(2))
        assertTrue(CompanionTlsIssuanceFlow.aliasFor(1) != CompanionTlsIssuanceFlow.aliasFor(2))
    }
}
