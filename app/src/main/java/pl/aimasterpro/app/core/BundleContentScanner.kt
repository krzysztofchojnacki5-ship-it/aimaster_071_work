package pl.aimasterpro.app.core

import java.io.File
import java.nio.charset.StandardCharsets

/** Bounded, local-only secret scan. Findings never contain matched values. */
object BundleContentScanner {
    data class Finding(val path: String, val code: String, val message: String)
    data class Report(val scannedFiles: Int, val scannedBytes: Long, val findings: List<Finding>) {
        val clean: Boolean get() = findings.isEmpty()
    }

    private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 40L * 1024L * 1024L
    private val secretPath = Regex("(?i)(^|/)(?:\\.env(?:\\..*)?|[^/]*(?:secret|credential)[^/]*|id_rsa|id_ed25519|[^/]*private[-_]?key[^/]*|[^/]*(?:api|access|refresh)[-_]?token[^/]*)$")
    private val patterns = listOf(
        "PRIVATE_KEY" to Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        "HIGH_ENTROPY_TOKEN" to Regex("(?i)\\b(?:sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,})\\b"),
        "BEARER_TOKEN" to Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}"),
        "SECRET_ASSIGNMENT" to Regex("(?i)\\b(?:api[_-]?(?:key|token)|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret[_-]?key|aws[_-]?secret[_-]?access[_-]?key|password|passwd)\\s*[:=]\\s*[\\\"']?[^\\s\\\"'`,;]{8,}")
    )

    fun scan(path: String, file: File): Report {
        val findings = mutableListOf<Finding>()
        if (secretPath.containsMatchIn(path)) {
            findings += Finding(path, "SENSITIVE_FILENAME", "Nazwa pliku wskazuje na możliwy sekret lub poświadczenie.")
        }
        if (!file.isFile || file.length() < 0L || file.length() > MAX_FILE_BYTES) {
            findings += Finding(path, "FILE_SIZE_OR_TYPE", "Plik nie jest zwykłym plikiem lub przekracza limit skanowania.")
            return Report(0, 0, findings)
        }
        val bytes = runCatching {
            file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream(minOf(file.length(), MAX_FILE_BYTES).toInt())
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total = Math.addExact(total, read.toLong())
                    require(total <= MAX_FILE_BYTES) { "Plik przekroczył limit podczas odczytu." }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.getOrElse {
            findings += Finding(path, "READ_ERROR_OR_SIZE_RACE", "Nie udało się bezpiecznie odczytać pliku w limicie skanowania.")
            return Report(0, 0, findings)
        }
        // Latin-1 preserves arbitrary bytes; removing NULs also exposes ASCII secrets embedded in UTF-16LE/BE.
        val content = String(bytes, StandardCharsets.ISO_8859_1).replace("\u0000", "")
        patterns.forEach { (code, regex) ->
            if (regex.containsMatchIn(content)) findings += Finding(path, code, "Wykryto wzorzec mogący zawierać sekret; wartość została ukryta.")
        }
        return Report(1, bytes.size.toLong(), findings.distinctBy { it.code })
    }

    fun combine(reports: List<Report>): Report {
        val bytes = reports.sumOf { it.scannedBytes }
        val findings = reports.flatMap { it.findings }.distinctBy { Triple(it.path, it.code, it.message) }
        return Report(reports.sumOf { it.scannedFiles }, bytes, findings)
    }

    fun validateTotal(report: Report): Report = if (report.scannedBytes <= MAX_TOTAL_BYTES) report else
        report.copy(findings = report.findings + Finding("<bundle>", "TOTAL_SCAN_LIMIT", "Łączny rozmiar skanowania przekracza limit 40 MB."))
}
