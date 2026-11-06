package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Fixture facts (checked with openssl): all test certificates are valid 2026-11-05 .. 2036-11-02,
 * so they exceed the 30-day design limit; positive tests therefore pass an explicit lifetime.
 * NOW is inside the validity window. Tests do not run in this environment (NOT RUN).
 */
class CompanionTlsLeafPolicyTest {
    private val now = 1_800_000_000_000L          // 2027-01-15, inside the fixture window
    private val expired = 2_200_000_000_000L      // after 2036-11-02
    private val notYetValid = 0L                  // 1970
    private val tenYearsPlus = 11L * 365L * 24L * 60L * 60L * 1000L

    private fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            javaClass.classLoader!!.getResourceAsStream("companion-tls/$name.pem")
        ) as X509Certificate

    private val root = cert("root-ca")
    private val server = cert("server")
    private val client = cert("client")
    private val wrongSan = cert("server-wrong-san")
    private val rogueServer = cert("rogue-server")

    private fun verdict(
        leaf: X509Certificate,
        expectedKeyFrom: X509Certificate = leaf,
        names: Set<String>,
        roots: Set<X509Certificate> = setOf(root),
        at: Long = now,
        eku: Set<String> = setOf(CompanionTlsLeafPolicy.EKU_SERVER_AUTH),
        lifetime: Long = tenYearsPlus,
        intermediates: List<X509Certificate> = emptyList()
    ) = CompanionTlsLeafPolicy.validate(
        leaf, intermediates, roots, expectedKeyFrom.publicKey, names, at,
        requiredEku = eku, maxLifetimeMillis = lifetime
    )

    @Test fun acceptsLeafSignedByPinnedRootWithMatchingKeyAndSan() {
        val result = verdict(server, names = setOf("localhost"))
        assertTrue(result.accepted)
        assertEquals("ACCEPTED", result.code)
    }

    @Test fun rejectsCaCertificateUsedAsLeaf() {
        assertEquals("LEAF_IS_CA", verdict(root, names = setOf("localhost")).code)
    }

    @Test fun rejectsLeafFromUntrustedRoot() {
        assertEquals("UNTRUSTED_ROOT", verdict(rogueServer, names = setOf("rogue-server")).code)
    }

    @Test fun rejectsSanMismatch() {
        assertEquals("SAN_MISMATCH", verdict(wrongSan, names = setOf("localhost")).code)
    }

    @Test fun rejectsKeyMismatchBetweenCsrKeyAndCertificate() {
        assertEquals("KEY_MISMATCH", verdict(server, expectedKeyFrom = client, names = setOf("localhost")).code)
    }

    @Test fun rejectsLifetimeAboveDesignLimitWithDefaultPolicy() {
        val result = CompanionTlsLeafPolicy.validate(
            server, emptyList(), setOf(root), server.publicKey, setOf("localhost"), now
        )
        assertEquals("LIFETIME_TOO_LONG", result.code)
    }

    @Test fun rejectsExpiredLeaf() {
        assertEquals("EXPIRED", verdict(server, names = setOf("localhost"), at = expired).code)
    }

    @Test fun rejectsNotYetValidLeaf() {
        assertEquals("NOT_YET_VALID", verdict(server, names = setOf("localhost"), at = notYetValid).code)
    }

    @Test fun rejectsLeafMissingRequiredEku() {
        val result = verdict(server, names = setOf("localhost"),
            eku = setOf(CompanionTlsLeafPolicy.EKU_CLIENT_AUTH))
        assertEquals("EKU_MISSING", result.code)
    }

    @Test fun rejectsWildcardExpectation() {
        assertEquals("INVALID_EXPECTATION", verdict(server, names = setOf("*.example")).code)
    }

    @Test fun rejectsEmptyTrustAnchors() {
        assertEquals("NO_TRUSTED_ROOT", verdict(server, names = setOf("localhost"), roots = emptySet()).code)
    }

    @Test fun clientLeafAcceptedForClientAuthRequirement() {
        val result = verdict(client, names = setOf("companion-client"),
            eku = setOf(CompanionTlsLeafPolicy.EKU_CLIENT_AUTH))
        assertTrue(result.code, result.accepted)
    }
}
