package pl.aimasterpro.app.core

/** Preflight rules for future workspace writes. This class validates proposals; it never writes files. */
object ChangeSafetyGuard {
    data class Finding(val code: String, val message: String, val blocking: Boolean)
    data class Validation(val normalizedPaths: List<String>, val findings: List<Finding>) {
        val allowed: Boolean get() = findings.none { it.blocking }
    }

    private val secretPatterns = listOf(
        Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        Regex("(?i)\\b(?:sk-[a-z0-9]{20,}|ghp_[a-z0-9]{20,}|github_pat_[a-z0-9_]{20,}|xox[baprs]-[a-z0-9-]{20,}|AKIA[0-9A-Z]{16})\\b"),
        Regex("AIza[0-9A-Za-z_-]{30,}"),
        Regex("(?i)(api[_-]?(?:key|token)|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret[_-]?key|aws[_-]?secret[_-]?access[_-]?key|password|passwd)\\s*[:=]\\s*[\"']?[^\\s\"']{8,}"),
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}")
    )
    private val protectedSegments = setOf(".git", ".gradle", ".idea", "build", "captures", "keystore", "secrets")
    private val protectedFiles = setOf("local.properties", "keystore.properties", ".env", "gradle.properties.local")

    fun validate(paths: List<String>, contentByPath: Map<String, String> = emptyMap(), maxFiles: Int = 50, maxTotalChars: Int = 2_000_000): Validation {
        val findings = mutableListOf<Finding>()
        if (paths.isEmpty()) findings += Finding("EMPTY_CHANGESET", "Zestaw zmian nie zawiera plików.", true)
        if (paths.size > maxFiles.coerceAtLeast(1)) findings += Finding("TOO_MANY_FILES", "Przekroczono limit plików w jednym zestawie zmian.", true)
        val normalized = mutableListOf<String>()
        paths.forEach { raw ->
            val path = raw.trim().replace('\\', '/')
            val segments = path.split('/')
            val invalid = path.isBlank() || path.startsWith('/') || path.startsWith("//") ||
                Regex("^[A-Za-z]:").containsMatchIn(path) || path.contains('\u0000') ||
                segments.any { it.isBlank() || it == "." || it == ".." }
            if (invalid) {
                findings += Finding("UNSAFE_PATH", "Odrzucono niebezpieczną ścieżkę: ${raw.take(160)}", true)
            } else {
                val lowerSegments = segments.map { it.lowercase() }
                val fileName = segments.last().lowercase()
                val sensitiveFile = fileName in protectedFiles || fileName.startsWith(".env") ||
                    fileName.endsWith(".jks") || fileName.endsWith(".keystore") || fileName.endsWith(".p12") ||
                    fileName.endsWith(".pem") || fileName.endsWith(".key") || fileName == "id_rsa"
                if (lowerSegments.any { it in protectedSegments } || sensitiveFile) {
                    findings += Finding("PROTECTED_PATH", "Ścieżka chroniona — wymagany osobny, jawny proces administracyjny: ${path.take(160)}", true)
                } else if (normalized.any { it.equals(path, true) }) {
                    findings += Finding("DUPLICATE_PATH", "Ścieżka występuje więcej niż raz: ${path.take(160)}", true)
                } else normalized += path
            }
        }
        val totalChars = contentByPath.values.sumOf { it.length.toLong() }
        if (totalChars > maxTotalChars) findings += Finding("CHANGESET_TOO_LARGE", "Treść zmian przekracza limit rozmiaru.", true)
        contentByPath.forEach { (path, content) ->
            secretPatterns.forEach { pattern ->
                if (pattern.containsMatchIn(content)) findings += Finding("SECRET_DETECTED", "Wykryto możliwy sekret w ${path.take(120)}. Usuń go z propozycji.", true)
            }
        }
        return Validation(normalized, findings.distinctBy { Triple(it.code, it.message, it.blocking) })
    }
}
