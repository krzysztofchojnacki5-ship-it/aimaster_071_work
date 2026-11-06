package pl.aimasterpro.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.aimasterpro.app.data.BackupCrypto

@RunWith(RobolectricTestRunner::class)
class BackupCryptoTest {
    @Test
    fun testEncryptDecrypt() {
        val plaintext = "{\"app\":\"AI_MASTER_PRO_ANDROID\",\"providers\":[]}"
        val password = "securepassword123".toCharArray()
        val encrypted = BackupCrypto.encrypt(plaintext, password)
        assertNotNull(encrypted)
        assertFalse(encrypted.contains(plaintext))

        val decrypted = BackupCrypto.decrypt(encrypted, password)
        assertEquals(plaintext, decrypted)
    }

    @Test(expected = Exception::class)
    fun testWrongPassword() {
        val plaintext = "secret data"
        val encrypted = BackupCrypto.encrypt(plaintext, "password123".toCharArray())
        BackupCrypto.decrypt(encrypted, "wrongpassword".toCharArray())
    }

    @Test(expected = Exception::class)
    fun testCorruptedJson() {
        BackupCrypto.decrypt("{invalid_json_envelope}", "password123".toCharArray())
    }

    @Test(expected = Exception::class)
    fun testInvalidEnvelopeFormat() {
        BackupCrypto.decrypt("{\"format\":\"UNKNOWN\",\"version\":1}", "password123".toCharArray())
    }

    @Test(expected = Exception::class)
    fun testMissingCryptoFields() {
        BackupCrypto.decrypt("{\"format\":\"AIMASTER_BACKUP\",\"version\":1}", "password123".toCharArray())
    }

    @Test(expected = Exception::class)
    fun testTamperedCiphertext() {
        val plaintext = "important config data"
        val password = "securepassword123".toCharArray()
        val encrypted = BackupCrypto.encrypt(plaintext, password)
        val tampered = encrypted.replace(Regex("\"data\":\\s*\"[^\"]+\""), "\"data\":\"dGFtcGVyZWRkYXRh\"")
        BackupCrypto.decrypt(tampered, password)
    }

    @Test
    fun testLargePayload() {
        val largeText = "x".repeat(50_000)
        val plaintext = "{\"app\":\"AI_MASTER_PRO_ANDROID\",\"data\":\"$largeText\"}"
        val password = "securepassword123".toCharArray()
        val encrypted = BackupCrypto.encrypt(plaintext, password)
        val decrypted = BackupCrypto.decrypt(encrypted, password)
        assertEquals(plaintext, decrypted)
    }
}
