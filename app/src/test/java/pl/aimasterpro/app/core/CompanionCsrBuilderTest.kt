package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Structural tests for the hand-encoded PKCS#10 request. The Python prototype with openssl was
 * used to validate the same encoding (self-signature verify OK; tampered copy fails). These JVM
 * tests are NOT RUN in this environment.
 */
class CompanionCsrBuilderTest {
    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun signWith(pair: KeyPair): (ByteArray) -> ByteArray = { data ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
    }

    /** Returns (headerLength, contentLength) of the DER element at offset. */
    private fun header(der: ByteArray, offset: Int): Pair<Int, Int> {
        var pos = offset + 1
        val first = der[pos].toInt() and 0xFF
        pos++
        if (first < 0x80) return Pair(pos - offset, first)
        val n = first and 0x7F
        var len = 0
        repeat(n) {
            len = (len shl 8) or (der[pos].toInt() and 0xFF)
            pos++
        }
        return Pair(pos - offset, len)
    }

    @Test fun signatureCoversExactlyTheEncodedRequestInfo() {
        val pair = ecKeyPair()
        val csr = CompanionCsrBuilder.build(
            "ai-master-companion", listOf("companion.local", "localhost"), pair.public, signWith(pair)
        )
        assertEquals(0x30.toByte(), csr[0])

        val (outerHeader, _) = header(csr, 0)
        val infoStart = outerHeader
        val (infoHeader, infoContent) = header(csr, infoStart)
        val info = csr.copyOfRange(infoStart, infoStart + infoHeader + infoContent)

        val algStart = infoStart + infoHeader + infoContent
        val (algHeader, algContent) = header(csr, algStart)
        val sigStart = algStart + algHeader + algContent
        assertEquals(0x03.toByte(), csr[sigStart])
        val (sigHeader, sigContent) = header(csr, sigStart)
        val signature = csr.copyOfRange(sigStart + sigHeader + 1, sigStart + sigHeader + sigContent)

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pair.public)
        verifier.update(info)
        assertTrue(verifier.verify(signature))
    }

    @Test fun subjectAndSanNamesAreEncoded() {
        val pair = ecKeyPair()
        val csr = CompanionCsrBuilder.build(
            "ai-master-companion", listOf("Companion.Local", "localhost"), pair.public, signWith(pair)
        )
        val text = String(csr, Charsets.ISO_8859_1)
        assertTrue(text.contains("ai-master-companion"))
        assertTrue(text.contains("companion.local"))   // normalized to lowercase
        assertTrue(text.contains("localhost"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWildcardSan() {
        val pair = ecKeyPair()
        CompanionCsrBuilder.build("ai-master-companion", listOf("*.example"), pair.public, signWith(pair))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptySanList() {
        val pair = ecKeyPair()
        CompanionCsrBuilder.build("ai-master-companion", emptyList(), pair.public, signWith(pair))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateSanNames() {
        val pair = ecKeyPair()
        CompanionCsrBuilder.build("ai-master-companion", listOf("localhost", "LOCALHOST"), pair.public, signWith(pair))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCommonName() {
        val pair = ecKeyPair()
        CompanionCsrBuilder.build("bad name;", listOf("localhost"), pair.public, signWith(pair))
    }

    @Test(expected = IllegalStateException::class)
    fun failsClosedWhenSignatureDoesNotMatchPublicKey() {
        val pair = ecKeyPair()
        val other = ecKeyPair()
        CompanionCsrBuilder.build("ai-master-companion", listOf("localhost"), pair.public, signWith(other))
    }
}
