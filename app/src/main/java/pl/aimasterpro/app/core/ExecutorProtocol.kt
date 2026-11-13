package pl.aimasterpro.app.core

import java.nio.charset.StandardCharsets
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAKey
import java.util.Base64

/**
 * Versioned, fail-closed wire contract for a future desktop companion executor.
 * This module does not start processes, create a sandbox, or promote ChangeSets.
 */
object ExecutorProtocol {
    const val PROTOCOL_VERSION = 1
    const val MAX_FILES = 3_000
    const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    const val MAX_BUNDLE_BYTES = 40L * 1024L * 1024L
    const val MAX_TIMEOUT_MS = 15L * 60L * 1_000L
    const val MAX_MEMORY_MB = 2_048
    const val MAX_CPU_CORES = 4
    const val RECEIPT_MAX_AGE_MS = 10L * 60L * 1_000L
    const val RECEIPT_FUTURE_SKEW_MS = 60L * 1_000L

    enum class Task(val gradleTask: String) {
        UNIT_TEST_DEBUG("testDebugUnitTest"),
        COMPILE_DEBUG("compileDebugKotlin")
    }

    data class BundleFile(val path: String, val sizeBytes: Long, val sha256: String)
    data class BundleManifest(
        val protocolVersion: Int,
        val bundleId: String,
        val workspaceId: String,
        val snapshotId: String,
        val createdAt: Long,
        val files: List<BundleFile>,
        val totalBytes: Long,
        val digest: String
    )
    data class ResourcePolicy(
        val timeoutMs: Long = MAX_TIMEOUT_MS,
        val memoryMb: Int = MAX_MEMORY_MB,
        val cpuCores: Int = MAX_CPU_CORES,
        val networkAllowed: Boolean = false
    )
    data class ExecutionRequest(
        val protocolVersion: Int,
        val runId: String,
        val manifest: BundleManifest,
        val task: Task,
        val policy: ResourcePolicy
    )
    data class ExecutionReceipt(
        val protocolVersion: Int,
        val runId: String,
        val bundleDigest: String,
        val task: Task,
        val executorId: String,
        val executorVersion: String,
        val startedAt: Long,
        val completedAt: Long,
        val exitCode: Int,
        val result: String,
        val sandboxVerified: Boolean,
        val networkDisabled: Boolean,
        val appliedTimeoutMs: Long,
        val appliedMemoryMb: Int,
        val appliedCpuCores: Int,
        val outputSha256: String,
        val signatureBase64: String
    )

    data class Validation(val valid: Boolean, val findings: List<String>)

    fun createManifest(
        bundleId: String,
        workspaceId: String,
        snapshotId: String,
        createdAt: Long,
        files: List<BundleFile>
    ): BundleManifest {
        val normalized = files.sortedBy { it.path }
        val provisional = BundleManifest(PROTOCOL_VERSION, bundleId, workspaceId, snapshotId,
            createdAt, normalized, normalized.sumOf { it.sizeBytes }, "")
        val validation = validateManifest(provisional, verifyDigest = false)
        require(validation.valid) { validation.findings.joinToString("; ") }
        return provisional.copy(digest = sha256(canonicalManifest(provisional)))
    }

    /** Stable binary manifest format shared with the desktop companion. */
    fun encodeManifest(manifest: BundleManifest): ByteArray {
        val validation = validateManifest(manifest)
        require(validation.valid) { validation.findings.joinToString("; ") }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(0x41494D45) // AIME
                out.writeInt(1) // binary format version
                out.writeInt(manifest.protocolVersion)
                out.writeUTF(manifest.bundleId)
                out.writeUTF(manifest.workspaceId)
                out.writeUTF(manifest.snapshotId)
                out.writeLong(manifest.createdAt)
                out.writeInt(manifest.files.size)
                manifest.files.forEach { file ->
                    out.writeUTF(file.path)
                    out.writeLong(file.sizeBytes)
                    out.writeUTF(file.sha256)
                }
                out.writeLong(manifest.totalBytes)
                out.writeUTF(manifest.digest)
            }
            bytes.toByteArray()
        }
    }

    /** Strict decoder: bounded counts/strings via manifest validation and rejects trailing bytes. */
    fun decodeManifest(bytes: ByteArray): BundleManifest {
        require(bytes.size in 1..(2 * 1024 * 1024)) { "Manifest poza limitem rozmiaru." }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0x41494D45) { "Nieprawidłowy magic manifestu." }
        require(input.readInt() == 1) { "Nieobsługiwany format binarny manifestu." }
        val protocol = input.readInt()
        val bundleId = input.readUTF()
        val workspaceId = input.readUTF()
        val snapshotId = input.readUTF()
        val createdAt = input.readLong()
        val count = input.readInt()
        require(count in 1..MAX_FILES) { "Liczba plików manifestu poza limitem." }
        val files = ArrayList<BundleFile>(count)
        repeat(count) { files += BundleFile(input.readUTF(), input.readLong(), input.readUTF()) }
        val total = input.readLong()
        val digest = input.readUTF()
        require(input.available() == 0) { "Manifest zawiera nadmiarowe dane." }
        val manifest = BundleManifest(protocol, bundleId, workspaceId, snapshotId, createdAt, files, total, digest)
        val validation = validateManifest(manifest)
        require(validation.valid) { validation.findings.joinToString("; ") }
        return manifest
    }

    fun validateManifest(manifest: BundleManifest, verifyDigest: Boolean = true): Validation {
        val errors = mutableListOf<String>()
        if (manifest.protocolVersion != PROTOCOL_VERSION) errors += "Nieobsługiwana wersja protokołu bundle."
        if (!safeId(manifest.bundleId) || !safeId(manifest.workspaceId) || !safeId(manifest.snapshotId)) errors += "Nieprawidłowy identyfikator bundle/workspace/snapshot."
        if (manifest.createdAt <= 0L) errors += "Nieprawidłowy czas utworzenia bundle."
        if (manifest.files.isEmpty() || manifest.files.size > MAX_FILES) errors += "Liczba plików poza dozwolonym zakresem."
        val seen = HashSet<String>()
        var total = 0L
        manifest.files.forEach { file ->
            if (!safeRelativePath(file.path)) errors += "Niebezpieczna lub wykluczona ścieżka: ${file.path.take(120)}"
            if (!seen.add(file.path.lowercase())) errors += "Powielona ścieżka: ${file.path.take(120)}"
            if (file.sizeBytes < 0L || file.sizeBytes > MAX_FILE_BYTES) errors += "Rozmiar pliku poza limitem: ${file.path.take(120)}"
            total = runCatching { Math.addExact(total, file.sizeBytes) }.getOrElse { Long.MAX_VALUE }
            if (!file.sha256.matches(Regex("[a-f0-9]{64}"))) errors += "Nieprawidłowy SHA-256: ${file.path.take(120)}"
        }
        if (total != manifest.totalBytes || total > MAX_BUNDLE_BYTES) errors += "Suma rozmiarów nie zgadza się z manifestem lub przekracza limit."
        if (manifest.files != manifest.files.sortedBy { it.path }) errors += "Lista plików nie jest kanonicznie posortowana."
        if (verifyDigest && (!manifest.digest.matches(Regex("[a-f0-9]{64}")) || sha256(canonicalManifest(manifest.copy(digest = ""))) != manifest.digest)) {
            errors += "Digest manifestu bundle jest nieprawidłowy."
        }
        return Validation(errors.isEmpty(), errors.distinct())
    }

    fun validateRequest(request: ExecutionRequest): Validation {
        val errors = validateManifest(request.manifest).findings.toMutableList()
        if (request.protocolVersion != PROTOCOL_VERSION) errors += "Nieobsługiwana wersja żądania."
        if (!safeId(request.runId)) errors += "Nieprawidłowy runId."
        val p = request.policy
        if (p.timeoutMs !in 1_000L..MAX_TIMEOUT_MS) errors += "Limit czasu poza polityką."
        if (p.memoryMb !in 256..MAX_MEMORY_MB) errors += "Limit RAM poza polityką."
        if (p.cpuCores !in 1..MAX_CPU_CORES) errors += "Limit CPU poza polityką."
        if (p.networkAllowed) errors += "Sieć musi być wyłączona dla zadań projektu."
        return Validation(errors.isEmpty(), errors.distinct())
    }

    /**
     * Verifies an executor-signed receipt against the app-pinned RSA public key.
     * A key supplied by the bundle/request is never accepted. This function only validates;
     * ChangeSetStore remains closed until a separately installed executor integration exists.
     */
    fun verifyReceipt(
        request: ExecutionRequest,
        receipt: ExecutionReceipt,
        pinnedExecutorKey: PublicKey,
        now: Long
    ): Validation {
        val errors = validateRequest(request).findings.toMutableList()
        if (receipt.protocolVersion != PROTOCOL_VERSION) errors += "Nieobsługiwana wersja receipt."
        if (receipt.runId != request.runId) errors += "Receipt dotyczy innego runId."
        if (receipt.bundleDigest != request.manifest.digest) errors += "Receipt dotyczy innego bundle."
        if (receipt.task != request.task) errors += "Receipt dotyczy innego zadania."
        if (!safeId(receipt.executorId) || !receipt.executorVersion.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) errors += "Nieprawidłowa tożsamość/version executora."
        if (receipt.startedAt <= 0L || receipt.completedAt < receipt.startedAt || receipt.completedAt > now + RECEIPT_FUTURE_SKEW_MS) errors += "Nieprawidłowe znaczniki czasu receipt."
        if (receipt.completedAt >= receipt.startedAt && receipt.completedAt - receipt.startedAt > receipt.appliedTimeoutMs + 5_000L) errors += "Czas wykonania przekracza zadeklarowany timeout executora."
        if (now - receipt.completedAt > RECEIPT_MAX_AGE_MS) errors += "Receipt jest przeterminowany."
        if (receipt.exitCode != 0 || receipt.result != "PASS") errors += "Executor nie zwrócił poprawnego PASS z kodem wyjścia 0."
        if (!receipt.sandboxVerified || !receipt.networkDisabled) errors += "Brak potwierdzenia sandboxa lub wyłączenia sieci."
        val policy = request.policy
        if (receipt.appliedTimeoutMs !in 1_000L..policy.timeoutMs || receipt.appliedMemoryMb !in 256..policy.memoryMb || receipt.appliedCpuCores !in 1..policy.cpuCores) {
            errors += "Executor nie zastosował zadeklarowanych limitów zasobów."
        }
        if (!receipt.outputSha256.matches(Regex("[a-f0-9]{64}"))) errors += "Nieprawidłowy digest logu wyjściowego."
        val rsaKeyStrongEnough = (pinnedExecutorKey as? RSAKey)?.modulus?.bitLength()?.let { it >= 2_048 } == true
        if (!rsaKeyStrongEnough) errors += "Klucz executora nie jest kluczem RSA o długości co najmniej 2048 bitów."
        val signatureBytes = runCatching { Base64.getDecoder().decode(receipt.signatureBase64) }.getOrNull()
        val signatureValid = rsaKeyStrongEnough && signatureBytes != null && runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(pinnedExecutorKey)
                update(canonicalReceipt(receipt).toByteArray(StandardCharsets.UTF_8))
                verify(signatureBytes)
            }
        }.getOrDefault(false)
        if (!signatureValid) errors += "Podpis receipt nie przechodzi weryfikacji kluczem zaufanym aplikacji."
        return Validation(errors.isEmpty(), errors.distinct())
    }

    fun canonicalReceipt(receipt: ExecutionReceipt): String = listOf(
        receipt.protocolVersion.toString(), receipt.runId, receipt.bundleDigest, receipt.task.name,
        receipt.executorId, receipt.executorVersion, receipt.startedAt.toString(), receipt.completedAt.toString(),
        receipt.exitCode.toString(), receipt.result, receipt.sandboxVerified.toString(), receipt.networkDisabled.toString(),
        receipt.appliedTimeoutMs.toString(), receipt.appliedMemoryMb.toString(), receipt.appliedCpuCores.toString(), receipt.outputSha256
    ).joinToString("\n")

    private fun canonicalManifest(manifest: BundleManifest): String = buildString {
        append(manifest.protocolVersion).append('\n').append(manifest.bundleId).append('\n')
        append(manifest.workspaceId).append('\n').append(manifest.snapshotId).append('\n').append(manifest.createdAt).append('\n')
        manifest.files.forEach { append(it.path).append('\t').append(it.sizeBytes).append('\t').append(it.sha256).append('\n') }
        append(manifest.totalBytes).append('\n')
    }

    private fun safeId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._-]{1,100}"))

    private fun safeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > 512 || path.startsWith('/') || path.contains('\\') ||
            path.contains('\u0000') || path.any { it.isISOControl() } || Regex("^[A-Za-z]:").containsMatchIn(path)) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        val lower = parts.map { it.lowercase() }
        val name = lower.last()
        val excludedDirs = setOf(".git", ".gradle", ".idea", "build", "captures", "keystore", "secrets", ".kotlin", ".ssh", ".aws")
        val excludedFile = name == "local.properties" || name == "keystore.properties" || name == "gradle.properties.local" ||
            name == ".env" || name.startsWith(".env.") || name == ".netrc" || name == ".npmrc" || name == ".pypirc" ||
            name == ".git-credentials" || name == "credentials.json" || name == "id_rsa" || name == "id_ed25519" ||
            name.endsWith(".jks") || name.endsWith(".keystore") || name.endsWith(".p12") || name.endsWith(".pem") || name.endsWith(".key")
        return lower.none { it in excludedDirs } && !excludedFile
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
