package pl.aimasterpro.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * PSK repository with real AES-GCM (JCE) standing in for the Android Keystore cipher.
 * NOT RUN in this environment.
 */
class CompanionPskRepositoryTest {
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

    private class JceCipher(private val key: SecretKey) : CompanionPskRepository.CompanionPskCipher {
        override fun encrypt(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            c.updateAAD(aad)
            return Pair(c.iv, c.doFinal(plain))
        }
        override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD(aad)
            return c.doFinal(ciphertext)
        }
    }

    private fun repo(blob: MemoryBlob = MemoryBlob()): Pair<CompanionPskRepository, MemoryBlob> {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return Pair(CompanionPskRepository(blob, JceCipher(key), SecureRandom()), blob)
    }

    @Test fun provisionThenLoadReturnsSameSecret() {
        val (repo, _) = repo()
        assertTrue(repo.provision("desktop-companion") is CompanionPskRepository.ProvisionResult.Provisioned)
        val loaded = repo.load("desktop-companion") as CompanionPskRepository.LoadResult.Active
        assertEquals(1, loaded.version)
        assertEquals(CompanionPskRepository.PSK_BYTES, loaded.secret.size)
    }

    @Test fun storedBlobDoesNotContainPlaintextSecret() {
        val (repo, blob) = repo()
        repo.provision("desktop-companion")
        val secret = (repo.load("desktop-companion") as CompanionPskRepository.LoadResult.Active).secret
        val stored = blob.content!!
        val contains = (0..stored.size - secret.size).any { i ->
            (secret.indices).all { j -> stored[i + j] == secret[j] }
        }
        assertFalse("PSK plaintext found in stored blob", contains)
    }

    @Test fun secondProvisionIsRefusedAndDoesNotReplaceSecret() {
        val (repo, blob) = repo()
        repo.provision("desktop-companion")
        val before = blob.content!!.copyOf()
        val result = repo.provision("desktop-companion") as CompanionPskRepository.ProvisionResult.Rejected
        assertEquals("ALREADY_PROVISIONED", result.code)
        assertArrayEquals(before, blob.content)
    }

    @Test fun loadRejectsDifferentPeer() {
        val (repo, _) = repo()
        repo.provision("desktop-companion")
        assertEquals("PEER_MISMATCH", (repo.load("other-peer") as CompanionPskRepository.LoadResult.Invalid).code)
    }

    @Test fun tamperedCiphertextFailsClosed() {
        val (repo, blob) = repo()
        repo.provision("desktop-companion")
        val bytes = blob.content!!
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        assertEquals("DECRYPT_FAILED", (repo.load("desktop-companion") as CompanionPskRepository.LoadResult.Invalid).code)
    }

    @Test fun corruptStoreIsReportedAndNeverOverwritten() {
        val garbage = ByteArray(48) { 9 }
        val (repo, blob) = repo(MemoryBlob(content = garbage))
        assertEquals("STORE_CORRUPT", (repo.provision("desktop-companion") as CompanionPskRepository.ProvisionResult.Rejected).code)
        assertArrayEquals(garbage, blob.content)
        assertEquals("STORE_CORRUPT", (repo.load("desktop-companion") as CompanionPskRepository.LoadResult.Invalid).code)
    }

    @Test fun absentWhenNothingStored() {
        val (repo, _) = repo()
        assertTrue(repo.load("desktop-companion") is CompanionPskRepository.LoadResult.Absent)
    }

    @Test fun writeFailureIsReportedNotSwallowed() {
        val (repo, _) = repo(MemoryBlob(failWrites = true))
        assertEquals("WRITE_FAILED", (repo.provision("desktop-companion") as CompanionPskRepository.ProvisionResult.Rejected).code)
    }

    @Test fun invalidPeerIdIsRejectedBeforeAnyStorageAccess() {
        val (repo, blob) = repo()
        val failed = runCatching { repo.provision("bad peer;") }.isFailure
        assertTrue(failed)
        assertEquals(null, blob.content)
    }
}
