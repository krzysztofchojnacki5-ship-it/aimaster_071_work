package pl.aimasterpro.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Chain codec and repository on JVM fixtures (companion-tls/ec, verified with openssl).
 * NOT RUN in this environment.
 */
class CompanionTlsChainRepositoryTest {
    private class MemoryBlob(var content: ByteArray? = null, var failWrites: Boolean = false) : CompanionTlsBlobStore {
        override fun read(): ByteArray? = content
        override fun clear(): Boolean {
            content = null
            return true
        }
        override fun write(bytes: ByteArray): Boolean {
            if (failWrites) return false
            content = bytes
            return true
        }
    }

    private fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(
            javaClass.classLoader!!.getResourceAsStream("companion-tls/ec/$name")!!
        ) as X509Certificate

    private val leaf = cert("ec-leaf.pem")
    private val root = cert("ec-root.pem")
    private val names = setOf("companion-test")
    private val withinValidity = leaf.notBefore.time + 86_400_000L

    private fun record(version: Int = 1) =
        CompanionTlsChainRecord(version, "ai_master_companion_tls_v$version", listOf(leaf))

    /** Returns the format-exception code raised by [block], or null if it did not throw. */
    private fun codeOf(block: () -> Unit): String? = try {
        block()
        null
    } catch (e: CompanionTlsChainFormatException) {
        e.code
    }

    @Test fun codecRoundTripPreservesVersionAliasAndChain() {
        val decoded = CompanionTlsChainCodec.decode(CompanionTlsChainCodec.encode(record()))
        assertEquals(1, decoded.version)
        assertEquals("ai_master_companion_tls_v1", decoded.alias)
        assertArrayEquals(leaf.encoded, decoded.leaf.encoded)
    }

    @Test fun codecDetectsCorruptedBytes() {
        val bytes = CompanionTlsChainCodec.encode(record())
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        assertEquals("DIGEST_MISMATCH", codeOf { CompanionTlsChainCodec.decode(bytes) })
    }

    @Test fun codecRejectsTruncatedInput() {
        assertEquals("TRUNCATED", codeOf { CompanionTlsChainCodec.decode(ByteArray(10)) })
    }

    @Test fun codecRejectsAliasThatDoesNotMatchVersion() {
        val mismatched = CompanionTlsChainRecord(2, "ai_master_companion_tls_v1", listOf(leaf))
        assertEquals("ALIAS_VERSION_MISMATCH", codeOf { CompanionTlsChainCodec.encode(mismatched) })
    }

    @Test fun saveThenLoadReturnsValidatedRecord() {
        val blob = MemoryBlob()
        val repo = CompanionTlsChainRepository(blob)
        assertEquals(CompanionTlsChainRepository.SaveResult.Saved, repo.save(record()))
        val result = repo.load(setOf(root), names, { leaf.publicKey }, withinValidity)
        assertTrue(result is CompanionTlsChainRepository.LoadResult.Valid)
    }

    @Test fun loadRejectsWhenKeyDoesNotMatchChain() {
        val repo = CompanionTlsChainRepository(MemoryBlob())
        repo.save(record())
        val result = repo.load(setOf(root), names, { root.publicKey }, withinValidity)
        assertEquals("KEY_MISMATCH", (result as CompanionTlsChainRepository.LoadResult.Invalid).code)
    }

    @Test fun loadRejectsExpiredChain() {
        val repo = CompanionTlsChainRepository(MemoryBlob())
        repo.save(record())
        val result = repo.load(setOf(root), names, { leaf.publicKey }, leaf.notAfter.time + 1L)
        assertEquals("EXPIRED", (result as CompanionTlsChainRepository.LoadResult.Invalid).code)
    }

    @Test fun loadFailsClosedWhenKeyUnavailable() {
        val repo = CompanionTlsChainRepository(MemoryBlob())
        repo.save(record())
        val result = repo.load(setOf(root), names, { null }, withinValidity)
        assertEquals("KEY_UNAVAILABLE", (result as CompanionTlsChainRepository.LoadResult.Invalid).code)
    }

    @Test fun saveRejectsNonIncreasingVersion() {
        val repo = CompanionTlsChainRepository(MemoryBlob())
        assertEquals(CompanionTlsChainRepository.SaveResult.Saved, repo.save(record(2)))
        val result = repo.save(record(1))
        assertEquals("VERSION_NOT_MONOTONIC", (result as CompanionTlsChainRepository.SaveResult.Rejected).code)
    }

    @Test fun saveNeverOverwritesCorruptStore() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34)
        val blob = MemoryBlob(content = garbage)
        val result = CompanionTlsChainRepository(blob).save(record())
        assertEquals("STORE_CORRUPT", (result as CompanionTlsChainRepository.SaveResult.Rejected).code)
        assertArrayEquals(garbage, blob.content)
    }

    @Test fun loadReportsCorruptStoreWithoutThrowing() {
        val repo = CompanionTlsChainRepository(MemoryBlob(content = ByteArray(40) { 7 }))
        val result = repo.load(setOf(root), names, { leaf.publicKey }, withinValidity)
        assertEquals("STORE_CORRUPT", (result as CompanionTlsChainRepository.LoadResult.Invalid).code)
    }

    @Test fun loadReportsAbsentWhenNothingStored() {
        val result = CompanionTlsChainRepository(MemoryBlob()).load(setOf(root), names, { leaf.publicKey }, withinValidity)
        assertTrue(result is CompanionTlsChainRepository.LoadResult.Absent)
    }

    @Test fun writeFailureIsReportedNotSwallowed() {
        val result = CompanionTlsChainRepository(MemoryBlob(failWrites = true)).save(record())
        assertEquals("WRITE_FAILED", (result as CompanionTlsChainRepository.SaveResult.Rejected).code)
    }
}
