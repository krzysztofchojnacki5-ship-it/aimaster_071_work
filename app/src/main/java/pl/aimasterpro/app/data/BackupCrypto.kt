package pl.aimasterpro.app.data

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected portable backup with rigorous validation. API keys are never exported as readable text. */
object BackupCrypto {
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val MAX_PLAIN_BYTES = 4 * 1024 * 1024
    private const val MAX_ENVELOPE_CHARS = 6 * 1024 * 1024
    private const val GCM_TAG_BITS = 128

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES").also { encoded.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: String, password: CharArray): String {
        require(password.size >= 8) { "Hasło musi mieć co najmniej 8 znaków." }
        require(plain.isNotBlank()) { "Tekst do zaszyfrowania nie może być pusty." }
        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        require(plainBytes.size <= MAX_PLAIN_BYTES) { "Kopia jest zbyt duża (limit 4 MiB)." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plainBytes)
        plainBytes.fill(0)
        return JSONObject().put("format", "AIMASTER_BACKUP").put("version", 1)
            .put("iterations", ITERATIONS).put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).toString(2)
    }

    fun decrypt(encoded: String, password: CharArray): String {
        require(password.size >= 8) { "Hasło musi mieć co najmniej 8 znaków." }
        require(encoded.length <= MAX_ENVELOPE_CHARS) { "Plik kopii przekracza limit 6 MiB." }
        val envelope = runCatching { JSONObject(encoded) }.getOrElse { throw IllegalArgumentException("Nieprawidłowy format JSON kopii zapasowej.") }
        require(envelope.optString("format") == "AIMASTER_BACKUP" && envelope.optInt("version") == 1) {
            "To nie jest poprawna kopia zapasowa AI Master Pro (nieznany format lub wersja)."
        }
        val iterationsValue = envelope.opt("iterations")
        require(!envelope.has("iterations") ||
            ((iterationsValue is Int || iterationsValue is Long) &&
                (iterationsValue as Number).toLong() == ITERATIONS.toLong())) {
            "Nieobsługiwana lub nieprawidłowa liczba iteracji KDF."
        }
        val saltBase64 = envelope.optString("salt", "")
        val ivBase64 = envelope.optString("iv", "")
        val dataBase64 = envelope.optString("data", "")
        require(saltBase64.isNotBlank() && ivBase64.isNotBlank() && dataBase64.isNotBlank()) {
            "Uszkodzona kopia zapasowa: brak wymaganych pól kryptograficznych."
        }
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        val data = Base64.decode(dataBase64, Base64.NO_WRAP)
        require(salt.size == 16) { "Nieprawidłowa długość soli (salt)." }
        require(iv.size == 12) { "Nieprawidłowa długość wektora inicjalizującego (IV)." }
        require(data.size in 17..(MAX_PLAIN_BYTES + 16)) { "Dane kopii mają nieprawidłowy rozmiar." }

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(data)
        require(plain.size <= MAX_PLAIN_BYTES) { "Dane kopii przekraczają dozwolony rozmiar." }
        return try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
    }
}
