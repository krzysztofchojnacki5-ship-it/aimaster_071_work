package pl.aimasterpro.app.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

/** Removes only a temporary directory entry created by the current writer.
 * CREATE_NEW ownership is the authority; a colliding pre-existing path must be preserved.
 * deleteIfExists unlinks the entry itself and never follows a symbolic-link target.
 */
internal fun cleanupOwnedTemporaryFile(temp: File, createdByThisWriter: Boolean) {
    if (createdByThisWriter) runCatching { Files.deleteIfExists(temp.toPath()) }
}

/**
 * Durable same-directory publication shared by the swap journal and patch receipt writers.
 * The publisher owns buffered flush and file-descriptor fsync after payload serialization.
 * Publication is strictly atomic: there is deliberately no non-atomic fallback. Directory fsync is best-effort
 * because Android filesystems/providers do not uniformly support opening directories.
 * The injectable operations are internal test seams; production callers use the defaults.
 */
internal fun publishWorkspaceDurableFile(
    temp: File,
    target: File,
    replaceExisting: Boolean,
    writePayload: (DataOutputStream) -> Unit,
    flushAndSync: (DataOutputStream, FileOutputStream) -> Unit = { output, stream ->
        output.flush()
        stream.fd.sync()
    },
    moveAtomically: (java.nio.file.Path, java.nio.file.Path, Boolean) -> Unit = { source, destination, replace ->
        if (replace) {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        }
    },
    syncDirectory: (File) -> Unit = { directory ->
        java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }
) {
    var ownsTemp = false
    try {
        require(temp.createNewFile()) { "Nie można bezpiecznie utworzyć pliku tymczasowego." }
        ownsTemp = true
        FileOutputStream(temp).use { stream ->
            DataOutputStream(BufferedOutputStream(stream)).use { output ->
                writePayload(output)
                flushAndSync(output, stream)
            }
        }
        moveAtomically(temp.toPath(), target.toPath(), replaceExisting)
        runCatching { syncDirectory(target.parentFile ?: temp.parentFile) }
    } finally {
        cleanupOwnedTemporaryFile(temp, ownsTemp)
    }
}

/**
 * Imports source archives into an app-private, isolated workspace.
 * It never edits the live app/project files and never executes imported content.
 */
class ProjectWorkspaceStore(private val root: File) {
    data class FileRecord(val path: String, val sizeBytes: Long, val sha256: String)
    data class WorkspaceInfo(
        val archiveName: String,
        val importedAt: Long,
        val fileCount: Int,
        val totalBytes: Long,
        val files: List<FileRecord>
    )
    data class SnapshotInfo(val id: String, val createdAt: Long, val fileCount: Int, val totalBytes: Long)
    data class IntegrityReport(val valid: Boolean, val checkedFiles: Int, val missing: List<String>, val modified: List<String>, val unexpected: List<String>)
    data class TextFile(val path: String, val content: String, val sha256: String)
    data class ApplyResult(
        val snapshot: SnapshotInfo,
        val workspace: WorkspaceInfo,
        val changedPaths: List<String>,
        val workspaceIdentity: String,
        val transactionId: String = ""
    )
    data class PendingPatchReceipt(
        val transactionId: String,
        val changeSetId: String,
        val chatId: String,
        val snapshotId: String,
        val beforeWorkspaceIdentity: String,
        val workspaceIdentity: String,
        val rolledBack: Boolean = false,
        val rollbackRequested: Boolean = false
    )
    data class SnapshotIntegrity(val snapshotId: String, val valid: Boolean, val checkedFiles: Int, val missing: List<String>, val modified: List<String>, val unexpected: List<String>, val workspace: WorkspaceInfo?)

    companion object {
        const val MAX_ARCHIVE_BYTES = 40L * 1024L * 1024L
        const val MAX_TOTAL_UNPACKED_BYTES = 80L * 1024L * 1024L
        const val MAX_SINGLE_FILE_BYTES = 8L * 1024L * 1024L
        const val MAX_ENTRIES = 3_000
        const val MAX_SNAPSHOTS = 5
        private const val MANIFEST_MAGIC = 0x41494D57 // AIMW
        private const val MANIFEST_VERSION = 1
        private const val SWAP_JOURNAL_MAGIC = 0x41495357 // AISW
        private const val SWAP_JOURNAL_VERSION = 2
        private const val MAX_SWAP_JOURNAL_BYTES = 16L * 1024L
        private const val PATCH_RECEIPT_MAGIC = 0x41495052 // AIPR
        private const val PATCH_RECEIPT_VERSION = 3
        private val excludedDirectories = setOf(".git", ".gradle", ".idea", "build", "captures", "keystore", "secrets")

        private fun excludedFile(name: String): Boolean {
            val n = name.lowercase()
            return n == "local.properties" || n == "keystore.properties" || n == "gradle.properties.local" ||
                n == ".env" || n.startsWith(".env.") || n.endsWith(".jks") || n.endsWith(".keystore") ||
                n.endsWith(".p12") || n.endsWith(".pem") || n.endsWith(".key") || n == "id_rsa"
        }
    }

    private data class PatchSwapMetadata(
        val changeSetId: String,
        val chatId: String,
        val snapshotId: String,
        val beforeWorkspaceIdentity: String
    )
    private data class SwapJournal(
        val swapId: String,
        val hadCurrent: Boolean,
        val expectedManifestSha256: String,
        val patch: PatchSwapMetadata? = null
    )

    private val currentDir get() = File(root, "current")
    private val currentManifest get() = File(root, "current.manifest")
    private val snapshotsDir get() = File(root, "snapshots")
    private val swapJournalFile get() = File(root, "workspace.swap.journal")
    private val patchReceiptFile get() = File(root, "workspace.patch.receipt")

    @Synchronized
    fun currentInfo(): WorkspaceInfo? {
        recoverInterruptedSwap()
        return readManifest(currentManifest)
    }

    /** SHA-256 identity of the exact current manifest; read-only to avoid deleting concurrent staging trees. */
    @Synchronized
    fun currentIdentity(): String? {
        if (!currentManifest.isFile || readManifest(currentManifest) == null) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(currentManifest).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /** True while a workspace swap or patch receipt still needs host reconciliation. */
    @Synchronized
    fun hasPendingWorkspaceTransaction(): Boolean = swapJournalFile.exists() || patchReceiptFile.exists()

    /** Returns the durable receipt of a patch committed before the caller could update its ledger. */
    @Synchronized
    fun readPendingPatchReceipt(): PendingPatchReceipt? {
        recoverInterruptedSwap()
        return readPatchReceiptFile()
    }

    private fun readPatchReceiptFile(): PendingPatchReceipt? {
        if (!patchReceiptFile.exists()) return null
        require(!Files.isSymbolicLink(patchReceiptFile.toPath()) &&
            patchReceiptFile.canonicalFile.parentFile == root.canonicalFile) {
            "Potwierdzenie patcha wskazuje poza prywatny katalog workspace."
        }
        DataInputStream(BufferedInputStream(FileInputStream(patchReceiptFile))).use { input ->
            require(input.readInt() == PATCH_RECEIPT_MAGIC) { "Nieprawidłowy nagłówek potwierdzenia patcha." }
            val version = input.readInt()
            require(version in 1..PATCH_RECEIPT_VERSION) { "Nieobsługiwana wersja potwierdzenia patcha." }
            val receipt = PendingPatchReceipt(
                transactionId = input.readUTF(),
                changeSetId = input.readUTF(),
                chatId = input.readUTF(),
                snapshotId = input.readUTF(),
                beforeWorkspaceIdentity = input.readUTF(),
                workspaceIdentity = input.readUTF(),
                rolledBack = if (version >= 2) input.readBoolean() else false,
                rollbackRequested = if (version >= 3) input.readBoolean() else false
            )
            require(input.read() == -1) { "Potwierdzenie patcha zawiera nadmiarowe dane." }
            require(receipt.transactionId.matches(Regex("[0-9a-fA-F-]{36}")) &&
                receipt.changeSetId.isNotBlank() && receipt.chatId.isNotBlank() &&
                receipt.snapshotId.matches(Regex("[A-Za-z0-9-]{1,100}")) &&
                receipt.beforeWorkspaceIdentity.matches(Regex("[0-9a-f]{64}")) &&
                receipt.workspaceIdentity.matches(Regex("[0-9a-f]{64}")) &&
                receipt.beforeWorkspaceIdentity != receipt.workspaceIdentity) {
                "Nieprawidłowe pola potwierdzenia patcha."
            }
            return receipt
        }
    }

    /** Removes only the exact receipt already reconciled by the host ledger. */
    @Synchronized
    fun acknowledgePatchReceipt(changeSetId: String, transactionId: String): Boolean {
        val receipt = readPendingPatchReceipt() ?: return true
        if (receipt.changeSetId != changeSetId || receipt.transactionId != transactionId) return false
        require(patchReceiptFile.delete()) { "Nie można usunąć uzgodnionego potwierdzenia patcha." }
        syncDirectoryBestEffort(root)
        return true
    }

    /** Durably requests rollback before the filesystem restore begins. */
    @Synchronized
    fun markPatchReceiptRollbackRequested(changeSetId: String, transactionId: String): Boolean {
        val receipt = readPendingPatchReceipt() ?: return false
        if (receipt.changeSetId != changeSetId || receipt.transactionId != transactionId) return false
        if (receipt.rolledBack || receipt.rollbackRequested) return true
        writePatchReceipt(receipt.copy(rollbackRequested = true), replaceExisting = true)
        return true
    }

    /** Marks a completed restore; safe to retry after process death. */
    @Synchronized
    fun markPatchReceiptRolledBack(changeSetId: String, transactionId: String): Boolean {
        val receipt = readPendingPatchReceipt() ?: return false
        if (receipt.changeSetId != changeSetId || receipt.transactionId != transactionId) return false
        if (receipt.rolledBack) return true
        require(receipt.rollbackRequested) { "Rollback nie został trwale zainicjowany w receipt." }
        require(currentIdentity() == receipt.beforeWorkspaceIdentity) {
            "Workspace nie ma bazowej identity; nie można oznaczyć patcha jako wycofanego."
        }
        writePatchReceipt(receipt.copy(rolledBack = true, rollbackRequested = false), replaceExisting = true)
        return true
    }

    /** Completes an interrupted, explicitly requested rollback only if identity is still exact. */
    @Synchronized
    fun recoverRequestedPatchRollback(changeSetId: String, transactionId: String): PendingPatchReceipt {
        val receipt = readPendingPatchReceipt() ?: error("Brak receipt do odzyskania rollbacku.")
        require(receipt.changeSetId == changeSetId && receipt.transactionId == transactionId) {
            "Receipt nie odpowiada żądanej transakcji rollbacku."
        }
        if (receipt.rolledBack) return receipt
        require(receipt.rollbackRequested) { "Receipt nie zawiera trwałego żądania rollbacku." }
        require(verifySnapshot(receipt.snapshotId).valid && snapshotIdentity(receipt.snapshotId) == receipt.beforeWorkspaceIdentity) {
            "Snapshot rollbacku jest uszkodzony lub nie odpowiada bazowej identity."
        }
        when (currentIdentity()) {
            receipt.workspaceIdentity -> restoreSnapshotForPatchRollback(receipt.snapshotId, changeSetId, transactionId)
            receipt.beforeWorkspaceIdentity -> Unit // restore completed before the process died
            else -> error("Workspace ma trzecią identity; rollback zablokowano, aby nie nadpisać późniejszego projektu.")
        }
        check(markPatchReceiptRolledBack(changeSetId, transactionId)) {
            "Nie udało się trwale oznaczyć odzyskanego rollbacku."
        }
        return readPendingPatchReceipt() ?: error("Receipt zniknął przed potwierdzeniem rollbacku.")
    }

    @Synchronized
    fun importZip(input: InputStream, archiveName: String, now: Long = System.currentTimeMillis()): WorkspaceInfo {
        require(archiveName.isNotBlank()) { "Brak nazwy archiwum." }
        require(root.exists() || root.mkdirs()) { "Nie można utworzyć prywatnego katalogu projektu." }
        recoverInterruptedSwap()
        require(!patchReceiptFile.exists()) {
            "Import zablokowany: poprzedni patch nie został jeszcze uzgodniony z rejestrem ChangeSet."
        }
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".staging-$id")
        val stagedManifest = File(root, ".staging-$id.manifest")
        if (staging.exists()) staging.deleteRecursively()
        require(staging.mkdirs()) { "Nie można utworzyć katalogu tymczasowego." }

        try {
            val records = extract(input, staging)
            require(records.isNotEmpty()) { "Archiwum nie zawiera plików źródłowych po odfiltrowaniu katalogów i sekretów." }
            val info = WorkspaceInfo(archiveName.take(240), now, records.size, records.sumOf { it.sizeBytes }, records)
            writeManifest(stagedManifest, info)

            if (currentDir.exists()) {
                val integrity = verifyCurrentWorkspaceInternal()
                require(integrity.valid) {
                    "Nie można zastąpić obszaru roboczego: wykryto zmiany poza manifestem (brak: ${integrity.missing.size}, zmienione: ${integrity.modified.size}, dodatkowe: ${integrity.unexpected.size}). Najpierw wyjaśnij różnice."
                }
                createSnapshotInternal(now)
            }
            replaceCurrent(staging, stagedManifest)
            pruneSnapshots()
            return info
        } catch (t: Throwable) {
            staging.deleteRecursively()
            stagedManifest.delete()
            throw t
        }
    }

    @Synchronized
    fun snapshots(): List<SnapshotInfo> {
        if (!snapshotsDir.isDirectory) return emptyList()
        return snapshotsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val info = readManifest(File(dir, "manifest.bin")) ?: return@mapNotNull null
            val createdAt = runCatching { DataInputStream(FileInputStream(File(dir, "snapshot.meta"))).use { it.readLong() } }.getOrNull()
                ?: dir.lastModified()
            SnapshotInfo(dir.name, createdAt, info.fileCount, info.totalBytes)
        }?.sortedByDescending { it.createdAt }.orEmpty()
    }

    /** Returns the exact manifest identity of a verified snapshot, or null when it is invalid. */
    @Synchronized
    fun snapshotIdentity(snapshotId: String): String? {
        val integrity = verifySnapshot(snapshotId)
        if (!integrity.valid) return null
        val manifest = File(File(snapshotsDir, snapshotId), "manifest.bin")
        return runCatching { sha256(manifest) }.getOrNull()
    }

    /** Verifies a snapshot independently from the active workspace. No files are changed. */
    @Synchronized
    fun verifySnapshot(snapshotId: String): SnapshotIntegrity {
        require(snapshotId.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Nieprawidłowy identyfikator snapshotu." }
        val dir = File(snapshotsDir, snapshotId)
        val project = File(dir, "project")
        val manifestFile = File(dir, "manifest.bin")
        val safeSnapshotLocation = runCatching {
            val workspaceRoot = root.canonicalFile.toPath()
            val snapshotsRoot = snapshotsDir.canonicalFile.toPath()
            val snapshotPath = dir.canonicalFile.toPath()
            val projectPath = project.canonicalFile.toPath()
            val manifestPath = manifestFile.canonicalFile.toPath()
            !Files.isSymbolicLink(snapshotsDir.toPath()) && !Files.isSymbolicLink(dir.toPath()) &&
                !Files.isSymbolicLink(project.toPath()) && !Files.isSymbolicLink(manifestFile.toPath()) &&
                snapshotsRoot.startsWith(workspaceRoot) && snapshotPath.startsWith(snapshotsRoot) &&
                projectPath.startsWith(snapshotPath) && manifestPath.startsWith(snapshotPath)
        }.getOrDefault(false)
        if (!safeSnapshotLocation) {
            return SnapshotIntegrity(snapshotId, false, 0, listOf("snapshot_path"), emptyList(), emptyList(), null)
        }
        val info = readManifest(manifestFile)
            ?: return SnapshotIntegrity(snapshotId, false, 0, listOf("manifest.bin"), emptyList(), emptyList(), null)
        val missing = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val expected = info.files.associateBy { it.path }
        val actual = linkedSetOf<String>()
        val canonicalRoot = runCatching { project.canonicalFile.toPath() }.getOrNull()
        if (!project.isDirectory || canonicalRoot == null) {
            return SnapshotIntegrity(snapshotId, false, info.fileCount, info.files.map { it.path }, emptyList(), emptyList(), info)
        }
        expected.forEach { (relative, record) ->
            val file = File(project, relative)
            val safe = runCatching {
                !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.toPath().startsWith(canonicalRoot) && file.isFile
            }.getOrDefault(false)
            if (!safe) missing += relative
            else {
                actual += relative
                if (file.length() != record.sizeBytes || sha256(file) != record.sha256) modified += relative
            }
        }
        project.walkTopDown().forEach { file ->
            if (file == project) return@forEach
            val symlink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)
            if (file.isFile || symlink) {
                val relative = project.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                if (relative !in expected) actual += relative
            }
        }
        val unexpected = actual.filter { it !in expected }.sorted()
        return SnapshotIntegrity(snapshotId, missing.isEmpty() && modified.isEmpty() && unexpected.isEmpty(),
            expected.size, missing, modified, unexpected, info)
    }

    /**
     * Restores a verified snapshot transactionally. The current workspace is first retained
     * as a safety snapshot; a corrupt snapshot is never installed.
     */
    @Synchronized
    fun restoreSnapshot(snapshotId: String, now: Long = System.currentTimeMillis()): WorkspaceInfo {
        require(!patchReceiptFile.exists()) {
            "Przywracanie zablokowane: poprzedni patch nie został jeszcze uzgodniony z rejestrem ChangeSet."
        }
        return restoreSnapshotInternal(snapshotId, now)
    }

    /** Explicit rollback path: only the exact pending patch transaction may use its snapshot. */
    @Synchronized
    fun restoreSnapshotForPatchRollback(
        snapshotId: String,
        changeSetId: String,
        transactionId: String,
        now: Long = System.currentTimeMillis()
    ): WorkspaceInfo {
        val receipt = readPatchReceiptFile() ?: error("Brak potwierdzenia patcha do rollbacku.")
        require(receipt.changeSetId == changeSetId && receipt.transactionId == transactionId &&
            receipt.snapshotId == snapshotId && receipt.rollbackRequested && !receipt.rolledBack) {
            "Żądanie rollbacku nie odpowiada dokładnie oczekującej transakcji patcha."
        }
        require(currentIdentity() == receipt.workspaceIdentity) {
            "Workspace ma inną identity niż zatwierdzony patch; rollback zablokowano, aby nie nadpisać późniejszego projektu."
        }
        require(verifyCurrentWorkspaceInternal().valid) {
            "Bieżący workspace nie przechodzi kontroli integralności; rollback zablokowano, aby nie nadpisać zmienionych plików."
        }
        return restoreSnapshotInternal(snapshotId, now)
    }

    private fun restoreSnapshotInternal(snapshotId: String, now: Long): WorkspaceInfo {
        val verified = verifySnapshot(snapshotId)
        require(verified.valid) {
            "Przywracanie zablokowane: snapshot jest uszkodzony (brak ${verified.missing.size}, zmienione ${verified.modified.size}, dodatkowe ${verified.unexpected.size})."
        }
        val snapshotDir = File(snapshotsDir, snapshotId)
        val sourceProject = File(snapshotDir, "project")
        val info = verified.workspace ?: error("Brak manifestu snapshotu.")
        require(root.exists() || root.mkdirs()) { "Nie można utworzyć katalogu workspace." }
        val currentIsHealthy = if (currentDir.isDirectory && currentManifest.isFile) {
            runCatching { verifyCurrentWorkspace().valid }.getOrDefault(false)
        } else false
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".patch-staging-restore-$id")
        val stagedManifest = File(root, ".patch-staging-restore-$id.manifest")
        require(staging.mkdirs()) { "Nie można utworzyć stagingu przywracania." }
        try {
            copyTreeBounded(sourceProject, staging, MAX_TOTAL_UNPACKED_BYTES)
            Files.copy(File(snapshotDir, "manifest.bin").toPath(), stagedManifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            // Re-parse and verify the staged copy before it can replace current/.
            val stagedInfo = readManifest(stagedManifest) ?: error("Manifest snapshotu jest nieprawidłowy.")
            verifyTreeAgainstManifest(staging, stagedInfo)
            // Preserve a healthy current tree as a safety snapshot. A failed/corrupt tree
            // is instead transactionally displaced by replaceCurrent and never re-snapshotted.
            if (currentIsHealthy) createSnapshotInternal(now)
            replaceCurrent(staging, stagedManifest)
            pruneSnapshots()
            return info
        } catch (t: Throwable) {
            staging.deleteRecursively()
            stagedManifest.delete()
            throw t
        }
    }

    /** Restores a previously verified snapshot without creating another snapshot of a failed apply. */
    private fun restoreSnapshotWithoutBackup(snapshotId: String) {
        val verified = verifySnapshot(snapshotId)
        require(verified.valid) { "Snapshot kompensacyjny jest uszkodzony; nie można bezpiecznie przywrócić workspace." }
        val snapshotDir = File(snapshotsDir, snapshotId)
        val sourceProject = File(snapshotDir, "project")
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".patch-recovery-$id")
        val stagedManifest = File(root, ".patch-recovery-$id.manifest")
        require(staging.mkdirs()) { "Nie można utworzyć stagingu odzyskiwania patcha." }
        try {
            copyTreeBounded(sourceProject, staging, MAX_TOTAL_UNPACKED_BYTES)
            Files.copy(File(snapshotDir, "manifest.bin").toPath(), stagedManifest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val stagedInfo = readManifest(stagedManifest) ?: error("Manifest snapshotu kompensacyjnego jest nieprawidłowy.")
            verifyTreeAgainstManifest(staging, stagedInfo)
            replaceCurrent(staging, stagedManifest)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            stagedManifest.delete()
            throw error
        }
    }

    private fun verifyTreeAgainstManifest(tree: File, info: WorkspaceInfo) {
        val rootPath = tree.canonicalFile.toPath()
        val expected = info.files.associateBy { it.path }
        val seen = mutableSetOf<String>()
        tree.walkTopDown().forEach { file ->
            if (file == tree) return@forEach
            require(!Files.isSymbolicLink(file.toPath())) { "Snapshot zawiera dowiązanie symboliczne." }
            if (file.isFile) {
                val relative = tree.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                val record = expected[relative] ?: error("Snapshot zawiera nieznany plik: $relative")
                require(file.canonicalFile.toPath().startsWith(rootPath)) { "Ścieżka snapshotu wychodzi poza katalog." }
                require(file.length() == record.sizeBytes && sha256(file) == record.sha256) { "Niezgodność SHA-256 snapshotu: $relative" }
                seen += relative
            }
        }
        require(seen == expected.keys) { "Snapshot nie zawiera kompletu plików z manifestu." }
    }

    data class BundleExportResult(
        val file: File,
        val bundleId: String,
        val snapshotId: String,
        val fileCount: Int,
        val totalBytes: Long,
        val manifestDigest: String,
        val archiveBytes: Long,
        val scannedFiles: Int,
        val scannedBytes: Long
    )

    /**
     * Exports only a separately verified snapshot. The active workspace is never read as
     * an export source and no project code is executed. Existing destinations are rejected.
     */
    @Synchronized
    fun exportSnapshotBundle(snapshotId: String, destination: File, now: Long = System.currentTimeMillis()): BundleExportResult {
        val verified = verifySnapshot(snapshotId)
        require(verified.valid) { "Eksport zablokowany: snapshot nie przechodzi kontroli integralności." }
        val info = verified.workspace ?: error("Brak danych snapshotu.")
        val snapshotDir = File(snapshotsDir, snapshotId)
        val projectDir = File(snapshotDir, "project")
        val destinationParent = destination.absoluteFile.parentFile ?: error("Brak katalogu docelowego.")
        require(destinationParent.exists() || destinationParent.mkdirs()) { "Nie można utworzyć katalogu docelowego." }
        val canonicalRoot = root.canonicalFile.toPath()
        val canonicalParent = destinationParent.canonicalFile.toPath()
        require(!canonicalParent.startsWith(canonicalRoot)) { "Bundle nie może być zapisany wewnątrz prywatnego workspace." }
        require(!Files.isSymbolicLink(destinationParent.toPath())) { "Katalog docelowy jest dowiązaniem symbolicznym." }
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "Plik docelowy już istnieje lub jest dowiązaniem symbolicznym; eksport nie nadpisuje danych." }
        val records = info.files.sortedBy { it.path }
        var scanBytes = 0L
        val scanReports = records.map { record ->
            val source = File(projectDir, record.path)
            val sourcePath = source.canonicalFile.toPath()
            val projectPath = projectDir.canonicalFile.toPath()
            require(!Files.isSymbolicLink(source.toPath()) && sourcePath.startsWith(projectPath) && source.isFile &&
                source.length() == record.sizeBytes && sha256(source) == record.sha256) {
                "Plik snapshotu nie przechodzi kontroli przed skanowaniem: ${record.path}"
            }
            BundleContentScanner.scan(record.path, source).also { report ->
                scanBytes = Math.addExact(scanBytes, report.scannedBytes)
                require(scanBytes <= ExecutorProtocol.MAX_BUNDLE_BYTES) {
                    "Eksport zablokowany: łączny rozmiar skanowania przekracza limit 40 MB."
                }
            }
        }
        val scan = BundleContentScanner.validateTotal(BundleContentScanner.combine(scanReports))
        require(scan.clean) {
            "Eksport zablokowany przez skan treści (${scan.findings.size} wykryć): " +
                scan.findings.take(8).joinToString("; ") { "${it.code} w ${it.path}" }
        }
        val bundleFiles = records.map { ExecutorProtocol.BundleFile(it.path, it.sizeBytes, it.sha256) }
        val bundleId = UUID.randomUUID().toString()
        val manifest = ExecutorProtocol.createManifest(bundleId, "workspace-$snapshotId", snapshotId, now, bundleFiles)
        val temp = File(destinationParent, ".${destination.name}.${UUID.randomUUID()}.tmp")
        require(temp.createNewFile()) { "Nie można bezpiecznie utworzyć pliku tymczasowego bundle." }
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.bin").apply { time = 0L })
                zip.write(ExecutorProtocol.encodeManifest(manifest))
                zip.closeEntry()
                records.forEach { record ->
                    val source = File(projectDir, record.path)
                    val sourcePath = source.canonicalFile.toPath()
                    val projectPath = projectDir.canonicalFile.toPath()
                    require(!Files.isSymbolicLink(source.toPath()) && sourcePath.startsWith(projectPath) && source.isFile) {
                        "Niebezpieczny plik snapshotu: ${record.path}"
                    }
                    require(source.length() == record.sizeBytes && sha256(source) == record.sha256) {
                        "Plik snapshotu zmienił się podczas eksportu: ${record.path}"
                    }
                    zip.putNextEntry(ZipEntry("project/${record.path}").apply { time = 0L })
                    FileInputStream(source).use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied = Math.addExact(copied, read.toLong())
                            require(copied <= record.sizeBytes) { "Plik urósł podczas eksportu: ${record.path}" }
                            zip.write(buffer, 0, read)
                        }
                        require(copied == record.sizeBytes) { "Niepełny odczyt pliku: ${record.path}" }
                    }
                    zip.closeEntry()
                }
            }
            require(temp.length() <= MAX_ARCHIVE_BYTES + (2L * 1024L * 1024L)) { "Archiwum bundle przekracza limit eksportu." }
            verifyExportedBundle(temp, manifest)
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath())
            }
            return BundleExportResult(destination, bundleId, snapshotId, records.size, info.totalBytes,
                manifest.digest, destination.length(), scan.scannedFiles, scan.scannedBytes)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun verifyExportedBundle(archive: File, expected: ExecutorProtocol.BundleManifest) {
        val seen = linkedSetOf<String>()
        var manifestSeen = false
        var total = 0L
        java.util.zip.ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && !entry.name.startsWith("/") && !entry.name.contains('\\') &&
                    entry.name.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Niebezpieczny wpis archiwum." }
                require(seen.add(entry.name)) { "Powielony wpis archiwum: ${entry.name.take(100)}" }
                if (entry.name == "manifest.bin") {
                    require(!manifestSeen) { "Powielony manifest." }
                    val bytes = readBounded(zip, 2L * 1024L * 1024L)
                    val decoded = ExecutorProtocol.decodeManifest(bytes)
                    require(decoded == expected) { "Manifest archiwum nie zgadza się z eksportem." }
                    manifestSeen = true
                } else {
                    require(entry.name.startsWith("project/")) { "Nieznany wpis archiwum." }
                    val relative = entry.name.removePrefix("project/")
                    val record = expected.files.firstOrNull { it.path == relative } ?: error("Nieznany plik w archiwum.")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        size = Math.addExact(size, read.toLong())
                        require(size <= record.sizeBytes) { "Wpis archiwum przekracza rozmiar z manifestu." }
                        digest.update(buffer, 0, read)
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    require(size == record.sizeBytes && hash == record.sha256) { "Niezgodność pliku w gotowym bundle: $relative" }
                    total = Math.addExact(total, size)
                }
                zip.closeEntry()
            }
        }
        require(manifestSeen && seen.size == expected.files.size + 1 && total == expected.totalBytes) {
            "Gotowe archiwum nie zawiera dokładnie zestawu plików z manifestu."
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total = Math.addExact(total, read.toLong())
            require(total <= maxBytes) { "Dane przekraczają limit odczytu manifestu." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** Returns a bounded, deterministic file list; file contents are deliberately not returned here. */
    @Synchronized
    fun listFiles(limit: Int = 500): List<FileRecord> = currentInfo()?.files.orEmpty().take(limit.coerceIn(1, MAX_ENTRIES))

    /**
     * Reads one manifest-owned source/text file only after path, size and SHA-256 checks.
     * Binary files, unknown extensions, oversized files and malformed UTF-8 are rejected.
     */
    @Synchronized
    fun readTextFile(relativePath: String, maxBytes: Int = 24 * 1024): TextFile {
        require(maxBytes in 1..(64 * 1024)) { "Limit odczytu musi mieścić się w zakresie 1–64 KB." }
        val normalized = normalizeEntryPath(relativePath, false)
            ?: throw IllegalArgumentException("Nieprawidłowa ścieżka pliku.")
        require(normalized == relativePath) { "Ścieżka musi być względna i znormalizowana." }
        val allowed = setOf("kt", "kts", "java", "xml", "json", "md", "txt", "gradle", "properties", "pro", "toml", "yaml", "yml", "html", "css", "js", "ts", "tsx", "jsx", "py", "sh", "bat", "sql", "csv", "gitignore", "editorconfig")
        val extension = normalized.substringAfterLast('/', normalized).substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        require(extension in allowed || normalized.substringAfterLast('/') in setOf(".gitignore", ".editorconfig")) {
            "Typ pliku nie jest dopuszczony do podglądu tekstowego."
        }
        val manifest = readManifest(currentManifest) ?: error("Brak poprawnego manifestu obszaru roboczego.")
        val record = manifest.files.firstOrNull { it.path == normalized } ?: error("Plik nie należy do aktualnego manifestu projektu.")
        require(record.sizeBytes <= maxBytes.toLong()) { "Plik jest większy niż limit podglądu (${maxBytes / 1024} KB)." }
        val file = File(currentDir, normalized)
        val rootPath = currentDir.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        require(filePath.startsWith(rootPath) && filePath != rootPath && file.isFile) { "Plik znajduje się poza obszarem roboczym lub nie istnieje." }
        require(file.length() == record.sizeBytes) { "Rozmiar pliku różni się od manifestu; odczyt zablokowany." }
        val actualHash = sha256(file)
        require(actualHash == record.sha256) { "SHA-256 pliku nie zgadza się z manifestem; odczyt zablokowany." }
        val bytes = FileInputStream(file).use { it.readBytes() }
        require(bytes.none { it == 0.toByte() }) { "Plik wygląda na binarny; podgląd zablokowany." }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val content = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val secretPatterns = listOf(
            Regex("(?i)(api[_-]?(?:key|token)|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret[_-]?key|private[_-]?key|aws[_-]?secret[_-]?access[_-]?key|password|passwd)\\s*[:=]\\s*[\"']?[^\\s\"']{8,}"),
            Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]{20,}"),
            Regex("\\b(sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b")
        )
        require(secretPatterns.none { it.containsMatchIn(content) }) { "Plik zawiera wzorzec przypominający sekret/API key; podgląd zablokowany." }
        return TextFile(normalized, content, actualHash)
    }

    /** Re-hashes the private workspace against its import manifest; performs no network or writes. */
    @Synchronized
    fun verifyCurrentWorkspace(): IntegrityReport {
        recoverInterruptedSwap()
        return verifyCurrentWorkspaceInternal()
    }

    /**
     * Applies only a previously accepted preview to the private workspace. It revalidates
     * the manifest and every base hash, creates an immutable pre-change snapshot, stages
     * the complete workspace copy, rebuilds the manifest, then swaps the staged tree in.
     * The app installation and original imported ZIP are never modified.
     */
    @Synchronized
    fun applyPreview(
        preview: ProjectPatchEngine.Preview,
        now: Long = System.currentTimeMillis(),
        changeSetId: String = "",
        chatId: String = ""
    ): ApplyResult {
        require(preview.accepted) { "Odrzucony preview nie może zostać zastosowany." }
        require(preview.files.isNotEmpty() && preview.files.size <= 20) { "Nieprawidłowa liczba plików w patchu." }
        require(!patchReceiptFile.exists()) {
            "Poprzedni patch ma nieuzgodnione potwierdzenie transakcji; najpierw odzyskaj rejestr ChangeSet."
        }
        val integrity = verifyCurrentWorkspace()
        require(integrity.valid) {
            "Zapis zablokowany: workspace nie przeszedł kontroli integralności (brak ${integrity.missing.size}, zmienione ${integrity.modified.size}, dodatkowe ${integrity.unexpected.size})."
        }
        val current = readManifest(currentManifest) ?: error("Brak manifestu bieżącego workspace.")
        val beforeWorkspaceIdentity = currentIdentity() ?: error("Brak tożsamości manifestu przed patchowaniem.")
        val expectedByPath = current.files.associateBy { it.path }
        val seen = mutableSetOf<String>()
        val contentByPath = linkedMapOf<String, String>()

        preview.files.forEach { file ->
            val normalized = normalizeEntryPath(file.path, false)
                ?: throw IllegalArgumentException("Niebezpieczna ścieżka patcha: ${file.path.take(160)}")
            require(normalized == file.path) { "Ścieżka patcha nie jest znormalizowana." }
            val extension = normalized.substringAfterLast('/', normalized).substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            val patchTextExtensions = setOf("kt", "kts", "java", "xml", "json", "md", "txt", "gradle", "properties", "pro", "toml", "yaml", "yml", "html", "css", "js", "ts", "tsx", "jsx", "py", "sh", "bat", "sql", "csv")
            require(extension in patchTextExtensions || normalized.substringAfterLast('/') in setOf(".gitignore", ".editorconfig")) {
                "Patch może zmieniać wyłącznie dozwolone pliki tekstowe: $normalized"
            }
            require(seen.add(normalized.lowercase(java.util.Locale.ROOT))) { "Patch zawiera ścieżkę powieloną." }
            val base = expectedByPath[normalized]
            when (file.operation) {
                "ADD" -> {
                    require(base == null && expectedByPath.keys.none { it.equals(normalized, true) } && file.baseSha256.isBlank()) {
                        "Dodawany plik już istnieje (także przy różnicy wielkości liter) lub ma niepusty hash bazowy: $normalized"
                    }
                    require(file.resultContent != null) { "Brak treści nowego pliku: $normalized" }
                }
                "MODIFY", "DELETE" -> {
                    require(base != null) { "Plik bazowy nie istnieje w manifeście: $normalized" }
                    require(base.sha256 == file.baseSha256) { "SHA-256 bazowy zmienił się od przygotowania patcha: $normalized" }
                    if (file.operation == "MODIFY") require(file.resultContent != null) { "Brak wynikowej treści modyfikowanego pliku: $normalized" }
                    if (file.operation == "DELETE") require(file.resultContent == null && file.resultSha256 == null) { "Nieprawidłowy wynik operacji DELETE: $normalized" }
                }
                else -> throw IllegalArgumentException("Nieobsługiwana operacja patcha: ${file.operation}")
            }
            file.resultContent?.let { content ->
                require('\u0000' !in content) { "Wynik patcha zawiera znak NUL: $normalized" }
                val bytes = Charsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(content))
                require(bytes.remaining().toLong() <= MAX_SINGLE_FILE_BYTES) { "Wynikowy plik przekracza limit 8 MB: $normalized" }
                val encoded = ByteArray(bytes.remaining())
                bytes.get(encoded)
                val actualHash = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
                require(actualHash == file.resultSha256) { "SHA-256 wynikowej treści nie zgadza się z preview: $normalized" }
                contentByPath[normalized] = content
            }
        }
        val safety = ChangeSafetyGuard.validate(preview.files.map { it.path }, contentByPath, maxFiles = 20, maxTotalChars = 2_000_000)
        require(safety.allowed) {
            "Dodatkowa kontrola bezpieczeństwa zablokowała zapis: ${safety.findings.filter { it.blocking }.joinToString("; ") { it.code + ": " + it.message }}"
        }

        val snapshot = createSnapshotInternal(now)
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".patch-staging-$id")
        val stagedManifest = File(root, ".patch-staging-$id.manifest")
        var installed = false
        try {
            require(staging.mkdirs()) { "Nie można utworzyć stagingu patcha." }
            copyTreeBounded(currentDir, staging, MAX_TOTAL_UNPACKED_BYTES)
            preview.files.forEach { change ->
                val target = File(staging, change.path)
                val canonicalRoot = staging.canonicalFile.toPath()
                val canonicalTarget = target.canonicalFile.toPath()
                require(canonicalTarget.startsWith(canonicalRoot) && canonicalTarget != canonicalRoot) {
                    "Ścieżka wynikowa wychodzi poza workspace: ${change.path}"
                }
                when (change.operation) {
                    "DELETE" -> require(target.isFile && target.delete()) { "Nie można usunąć pliku w stagingu: ${change.path}" }
                    "ADD", "MODIFY" -> {
                        val content = change.resultContent ?: error("Brak wynikowej treści: ${change.path}")
                        require(change.operation != "ADD" || !target.exists()) { "Plik dodawany pojawił się już w stagingu: ${change.path}" }
                        require(change.operation != "MODIFY" || target.isFile) { "Modyfikowany plik zniknął ze stagingu: ${change.path}" }
                        require(target.parentFile?.let { it.exists() || it.mkdirs() } == true) { "Nie można utworzyć katalogu: ${change.path}" }
                        val temp = File(target.parentFile, ".${target.name}.patch-$id.tmp")
                        try {
                            publishWorkspaceDurableFile(
                                temp = temp,
                                target = target,
                                replaceExisting = change.operation == "MODIFY",
                                writePayload = { output -> output.write(content.toByteArray(Charsets.UTF_8)) }
                            )
                        } finally {
                            if (temp.exists()) temp.delete()
                        }
                    }
                }
            }

            val records = mutableListOf<FileRecord>()
            var totalBytes = 0L
            staging.walkTopDown().forEach { file ->
                if (file == staging) return@forEach
                require(!Files.isSymbolicLink(file.toPath())) { "Symlink wykryty w stagingu: ${file.name}" }
                if (file.isFile) {
                    val relative = staging.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    require(normalizeEntryPath(relative, false) == relative) { "Nieprawidłowa ścieżka po patchu: $relative" }
                    val length = file.length()
                    require(length <= MAX_SINGLE_FILE_BYTES) { "Plik przekracza limit 8 MB po patchu: $relative" }
                    totalBytes += length
                    require(totalBytes <= MAX_TOTAL_UNPACKED_BYTES) { "Workspace przekracza limit 80 MB po patchu." }
                    records += FileRecord(relative, length, sha256(file))
                    require(records.size <= MAX_ENTRIES) { "Workspace przekracza limit $MAX_ENTRIES plików." }
                }
            }
            require(records.isNotEmpty()) { "Patch nie może usunąć ostatniego pliku workspace." }
            val updated = WorkspaceInfo(current.archiveName, current.importedAt, records.size, totalBytes, records.sortedBy { it.path })
            writeManifest(stagedManifest, updated)
            require(sha256(stagedManifest) != beforeWorkspaceIdentity) {
                "Patch nie zmienia manifestu workspace; pusty/no-op patch nie może zostać zapisany jako zastosowany."
            }
            val patchMetadata = if (changeSetId.isNotBlank() || chatId.isNotBlank()) {
                require(changeSetId.isNotBlank() && chatId.isNotBlank()) { "Niepełny kontekst ChangeSet/chat dla trwałego potwierdzenia patcha." }
                PatchSwapMetadata(changeSetId, chatId, snapshot.id, beforeWorkspaceIdentity)
            } else null
            // From this point a durable swap intent may be published. Keep the source snapshot
            // even if replaceCurrent reports an uncertain outcome; recovery/ledger reconciliation
            // needs that exact pre-change state.
            installed = true
            val swapTransactionId = replaceCurrent(staging, stagedManifest, patchMetadata)
            val appliedIdentity = currentIdentity() ?: error("Nie można odczytać tożsamości manifestu po zastosowaniu patcha.")
            check(readManifest(currentManifest) == updated) {
                "Workspace został równolegle zastąpiony podczas kończenia patcha; aktywny zakres nie może zostać przypisany."
            }
            // Keep the pre-change snapshot alive until the post-swap identity check has passed;
            // a failure above must still have a verified rollback source.
            runCatching { pruneSnapshots(snapshot.id) } // Snapshot pruning is non-critical after a verified commit.
            return ApplyResult(snapshot, updated, preview.files.map { it.path }, appliedIdentity, swapTransactionId)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            stagedManifest.delete()
            if (installed) {
                val recovery = runCatching { restoreSnapshotWithoutBackup(snapshot.id) }
                if (recovery.isFailure) {
                    val failure = IllegalStateException(
                        "Patch nie zakończył się poprawnie, a automatyczne przywrócenie snapshotu również zawiodło: ${recovery.exceptionOrNull()?.message ?: "błąd odzyskiwania"}",
                        error
                    )
                    recovery.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            } else {
                File(snapshotsDir, snapshot.id).deleteRecursively()
            }
            throw error
        }
    }

    /** Internal verification must not run recovery cleanup while an import staging tree is active. */
    private fun verifyCurrentWorkspaceInternal(): IntegrityReport {
        val manifest = readManifest(currentManifest)
            ?: return IntegrityReport(false, 0, listOf("current.manifest"), emptyList(), emptyList())
        val expected = manifest.files.associateBy { it.path }
        val missing = mutableListOf<String>()
        val modified = mutableListOf<String>()
        val actual = mutableSetOf<String>()
        expected.forEach { (relative, record) ->
            val file = File(currentDir, relative)
            val canonical = runCatching { file.canonicalFile.toPath().startsWith(currentDir.canonicalFile.toPath()) }.getOrDefault(false)
            val symlink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)
            if (!canonical || symlink || !file.isFile) {
                missing += relative
            } else {
                actual += relative
                if (file.length() != record.sizeBytes || sha256(file) != record.sha256) modified += relative
            }
        }
        currentDir.walkTopDown().forEach { file ->
            if (file == currentDir) return@forEach
            val symlink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)
            if (file.isFile || symlink) {
                val relative = currentDir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                if (relative !in expected) actual += relative
            }
        }
        val unexpected = actual.filter { it !in expected }.sorted()
        return IntegrityReport(missing.isEmpty() && modified.isEmpty() && unexpected.isEmpty(), expected.size, missing, modified, unexpected)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(input: InputStream, destination: File): List<FileRecord> {
        val records = mutableListOf<FileRecord>()
        val seen = HashSet<String>()
        var totalBytes = 0L
        var entries = 0
        val bounded = LimitedInputStream(input, MAX_ARCHIVE_BYTES)
        ZipInputStream(BufferedInputStream(bounded)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "Archiwum zawiera zbyt wiele wpisów (limit $MAX_ENTRIES)." }
                val raw = entry.name
                val normalized = normalizeEntryPath(raw, entry.isDirectory)
                if (entry.isDirectory) {
                    if (normalized != null && normalized.split('/').none { it.lowercase() in excludedDirectories || excludedFile(it) }) {
                        File(destination, normalized).mkdirs()
                    }
                    zip.closeEntry()
                    continue
                }
                require(normalized != null) { "Archiwum zawiera niebezpieczną ścieżkę: ${raw.take(140)}" }
                val segments = normalized.split('/')
                if (segments.any { it.lowercase() in excludedDirectories || excludedFile(it) }) {
                    zip.closeEntry()
                    continue
                }
                val collisionKey = normalized.lowercase(java.util.Locale.ROOT)
                require(seen.add(collisionKey)) { "Duplikat ścieżki w archiwum: ${normalized.take(140)}" }
                val target = File(destination, normalized)
                val canonicalRoot = destination.canonicalFile.toPath()
                val canonicalTarget = target.canonicalFile.toPath()
                require(canonicalTarget.startsWith(canonicalRoot) && canonicalTarget != canonicalRoot) {
                    "Ścieżka wychodzi poza obszar roboczy: ${raw.take(140)}"
                }
                require(target.parentFile?.let { it.exists() || it.mkdirs() } == true) { "Nie można utworzyć katalogu dla $normalized" }
                require(entry.size < 0L || entry.size <= MAX_SINGLE_FILE_BYTES) { "Plik przekracza limit 8 MB: $normalized" }
                val digest = MessageDigest.getInstance("SHA-256")
                var fileBytes = 0L
                BufferedOutputStream(FileOutputStream(target)).use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        fileBytes += read
                        totalBytes += read
                        require(fileBytes <= MAX_SINGLE_FILE_BYTES) { "Plik przekracza limit 8 MB: $normalized" }
                        require(totalBytes <= MAX_TOTAL_UNPACKED_BYTES) { "Rozpakowana zawartość przekracza limit 80 MB." }
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
                require(entry.size < 0L || entry.size == fileBytes) { "Niezgodny rozmiar wpisu ZIP: $normalized" }
                records += FileRecord(normalized, fileBytes, digest.digest().joinToString("") { "%02x".format(it) })
                zip.closeEntry()
            }
        }
        return records.sortedBy { it.path }
    }

    private fun normalizeEntryPath(raw: String, directory: Boolean): String? {
        if (raw.isBlank() || raw.length > 240 || raw.indexOf('\u0000') >= 0 || raw.contains('\\')) return null
        val path = if (directory) raw.trimEnd('/') else raw
        if (path.isBlank() || path.startsWith('/') || path.startsWith("//") || Regex("^[A-Za-z]:").containsMatchIn(path)) return null
        val parts = path.split('/')
        if (parts.size > 30 || parts.any { it.isBlank() || it == "." || it == ".." || ':' in it || it.any { ch -> ch.code < 32 } }) return null
        return parts.joinToString("/")
    }

    private fun createSnapshotInternal(now: Long): SnapshotInfo {
        require(currentDir.isDirectory) { "Brak aktualnego obszaru roboczego." }
        val info = readManifest(currentManifest) ?: error("Brak poprawnego manifestu bieżącego projektu.")
        val id = "${now}-${UUID.randomUUID().toString().take(8)}"
        val snapshot = File(snapshotsDir, id)
        require(snapshot.mkdirs()) { "Nie można utworzyć kopii zapasowej projektu." }
        try {
            copyTreeBounded(currentDir, File(snapshot, "project"), MAX_TOTAL_UNPACKED_BYTES)
            Files.copy(currentManifest.toPath(), File(snapshot, "manifest.bin").toPath(), StandardCopyOption.REPLACE_EXISTING)
            DataOutputStream(FileOutputStream(File(snapshot, "snapshot.meta"))).use { it.writeLong(now) }
        } catch (t: Throwable) {
            snapshot.deleteRecursively()
            throw t
        }
        return SnapshotInfo(id, now, info.fileCount, info.totalBytes)
    }

    private fun copyTreeBounded(source: File, target: File, maxBytes: Long) {
        var copied = 0L
        source.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = source.toPath().relativize(file.toPath()).toString()
            val out = File(target, relative)
            require(out.parentFile?.let { it.exists() || it.mkdirs() } == true) { "Nie można przygotować kopii." }
            copied += file.length()
            require(copied <= maxBytes) { "Kopia zapasowa przekracza limit." }
            Files.copy(file.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Replaces current/ using a durable intent record. The journal is written and fsynced
     * before the first rename, so process death at any swap boundary is recoverable.
     */
    private fun replaceCurrent(staging: File, stagedManifest: File, patch: PatchSwapMetadata? = null): String {
        val swapId = UUID.randomUUID().toString()
        val old = File(root, ".previous-$swapId")
        val oldManifest = File(root, ".previous-$swapId.manifest")
        val hadCurrent = currentDir.exists()
        require(!swapJournalFile.exists()) { "Istnieje nierozliczona transakcja workspace; wymagane odzyskiwanie." }
        require(staging.isDirectory && stagedManifest.isFile) { "Brak kompletnego stagingu transakcji workspace." }
        val expectedManifestHash = sha256(stagedManifest)
        if (patch != null) {
            require(!patchReceiptFile.exists()) { "Poprzedni patch ma nieuzgodnione potwierdzenie; nowa operacja jest zablokowana." }
            require(patch.snapshotId.matches(Regex("[A-Za-z0-9-]{1,100}")) &&
                patch.beforeWorkspaceIdentity.matches(Regex("[0-9a-f]{64}"))) {
                "Nieprawidłowe metadane transakcji patcha."
            }
        }
        val journal = SwapJournal(swapId, hadCurrent, expectedManifestHash, patch)
        writeSwapJournal(journal)

        try {
            if (hadCurrent) {
                require(currentDir.renameTo(old)) { "Nie można odłączyć poprzedniego obszaru roboczego." }
                if (currentManifest.exists() && !currentManifest.renameTo(oldManifest)) {
                    old.renameTo(currentDir)
                    error("Nie można zabezpieczyć manifestu poprzedniego projektu.")
                }
            }
            require(staging.renameTo(currentDir)) { "Nie można zainstalować katalogu workspace." }
            require(stagedManifest.renameTo(currentManifest)) { "Nie można zainstalować manifestu workspace." }

            val installed = readManifest(currentManifest) ?: error("Nowy manifest workspace jest nieprawidłowy.")
            require(sha256(currentManifest) == expectedManifestHash) { "Nowy manifest workspace nie zgadza się z intencją transakcji." }
            verifyTreeAgainstManifest(currentDir, installed)
            patch?.let { metadata ->
                writePatchReceipt(PendingPatchReceipt(
                    transactionId = swapId,
                    changeSetId = metadata.changeSetId,
                    chatId = metadata.chatId,
                    snapshotId = metadata.snapshotId,
                    beforeWorkspaceIdentity = metadata.beforeWorkspaceIdentity,
                    workspaceIdentity = expectedManifestHash,
                    rolledBack = false
                ))
            }

            // A valid installed pair is the commit point. Recovery can now safely finish cleanup.
            deleteSwapJournal()
            old.deleteRecursively()
            oldManifest.delete()
            return swapId
        } catch (error: Throwable) {
            val recovery = runCatching { recoverInterruptedSwap() }
            if (recovery.isFailure) {
                val failure = IllegalStateException(
                    "Transakcja workspace nie powiodła się, a odzyskiwanie również zawiodło: ${recovery.exceptionOrNull()?.message ?: "błąd odzyskiwania"}",
                    error
                )
                recovery.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            val committedAfterRecovery = runCatching {
                currentDir.isDirectory && currentManifest.isFile &&
                    sha256(currentManifest) == expectedManifestHash &&
                    (readManifest(currentManifest)?.let { verifyTreeAgainstManifest(currentDir, it); true } == true) &&
                    (patch == null || readPendingPatchReceipt()?.let {
                        it == PendingPatchReceipt(
                            transactionId = swapId,
                            changeSetId = patch.changeSetId,
                            chatId = patch.chatId,
                            snapshotId = patch.snapshotId,
                            beforeWorkspaceIdentity = patch.beforeWorkspaceIdentity,
                            workspaceIdentity = expectedManifestHash,
                            rolledBack = false
                        )
                    } == true)
            }.getOrDefault(false)
            if (committedAfterRecovery) return swapId
            throw error
        }
    }

    private fun writeSwapJournal(journal: SwapJournal) {
        val temp = File(root, ".workspace.swap.${journal.swapId}.tmp")
        try {
            publishWorkspaceDurableFile(
                temp = temp,
                target = swapJournalFile,
                replaceExisting = true,
                writePayload = { out ->
                    out.writeInt(SWAP_JOURNAL_MAGIC)
                    out.writeInt(SWAP_JOURNAL_VERSION)
                    out.writeUTF(journal.swapId)
                    out.writeBoolean(journal.hadCurrent)
                    out.writeUTF(journal.expectedManifestSha256)
                    out.writeBoolean(journal.patch != null)
                    journal.patch?.let { patch ->
                        out.writeUTF(patch.changeSetId)
                        out.writeUTF(patch.chatId)
                        out.writeUTF(patch.snapshotId)
                        out.writeUTF(patch.beforeWorkspaceIdentity)
                    }
                },
                syncDirectory = ::syncDirectoryBestEffort
            )
        } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
            throw IllegalStateException("System plików nie obsługuje atomowego zapisu dziennika transakcji.", unsupported)
        }
    }

    private fun readSwapJournal(): SwapJournal {
        require(!Files.isSymbolicLink(swapJournalFile.toPath()) &&
            swapJournalFile.isFile &&
            swapJournalFile.canonicalFile.parentFile == root.canonicalFile) {
            "Dziennik workspace wskazuje poza prywatny katalog aplikacji."
        }
        require(swapJournalFile.length() in 1L..MAX_SWAP_JOURNAL_BYTES) {
            "Dziennik workspace ma nieprawidłowy rozmiar."
        }
        DataInputStream(BufferedInputStream(FileInputStream(swapJournalFile))).use { input ->
            require(input.readInt() == SWAP_JOURNAL_MAGIC) { "Nieprawidłowy nagłówek dziennika workspace." }
            val version = input.readInt()
            require(version in 1..SWAP_JOURNAL_VERSION) { "Nieobsługiwana wersja dziennika workspace." }
            val id = input.readUTF()
            val hadCurrent = input.readBoolean()
            val expectedHash = input.readUTF()
            val patch = if (version >= 2 && input.readBoolean()) {
                PatchSwapMetadata(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF())
            } else null
            require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Nieprawidłowy identyfikator transakcji workspace." }
            require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "Nieprawidłowy hash docelowego manifestu." }
            patch?.let {
                require(it.changeSetId.isNotBlank() && it.chatId.isNotBlank() &&
                    it.snapshotId.matches(Regex("[A-Za-z0-9-]{1,100}")) &&
                    it.beforeWorkspaceIdentity.matches(Regex("[0-9a-f]{64}"))) {
                    "Nieprawidłowe metadane patcha w dzienniku workspace."
                }
            }
            require(input.read() == -1) { "Dziennik workspace zawiera nadmiarowe dane." }
            return SwapJournal(id, hadCurrent, expectedHash, patch)
        }
    }

    private fun ensurePatchReceipt(journal: SwapJournal, rolledBack: Boolean) {
        val metadata = journal.patch ?: return
        val expected = PendingPatchReceipt(
            transactionId = journal.swapId,
            changeSetId = metadata.changeSetId,
            chatId = metadata.chatId,
            snapshotId = metadata.snapshotId,
            beforeWorkspaceIdentity = metadata.beforeWorkspaceIdentity,
            workspaceIdentity = journal.expectedManifestSha256,
            rolledBack = rolledBack
        )
        val existing = if (patchReceiptFile.exists()) readPatchReceiptFile() else null
        if (existing != null) {
            require(existing == expected) { "Istniejące potwierdzenie patcha nie odpowiada nierozliczonej transakcji workspace." }
        } else {
            writePatchReceipt(expected)
        }
    }

    private fun writePatchReceipt(receipt: PendingPatchReceipt, replaceExisting: Boolean = false) {
        require(replaceExisting || !patchReceiptFile.exists()) { "Istnieje nieuzgodnione potwierdzenie wcześniejszego patcha." }
        val temp = File(root, ".workspace.patch.receipt.${receipt.transactionId}.tmp")
        // A process may die after fsync(temp) but before ATOMIC_MOVE. The journal proves this
        // exact transaction is being recovered, so this transaction-specific stale temp is safe to remove.
        if (temp.exists()) require(temp.delete()) { "Nie można usunąć niekompletnego potwierdzenia patcha." }
        try {
            publishWorkspaceDurableFile(
                temp = temp,
                target = patchReceiptFile,
                replaceExisting = replaceExisting,
                writePayload = { out ->
                        out.writeInt(PATCH_RECEIPT_MAGIC)
                    out.writeInt(PATCH_RECEIPT_VERSION)
                    out.writeUTF(receipt.transactionId)
                    out.writeUTF(receipt.changeSetId)
                    out.writeUTF(receipt.chatId)
                    out.writeUTF(receipt.snapshotId)
                    out.writeUTF(receipt.beforeWorkspaceIdentity)
                    out.writeUTF(receipt.workspaceIdentity)
                    out.writeBoolean(receipt.rolledBack)
                    out.writeBoolean(receipt.rollbackRequested)
                },
                syncDirectory = ::syncDirectoryBestEffort
            )
        } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
            throw IllegalStateException("System plików nie obsługuje atomowego zapisu potwierdzenia patcha.", unsupported)
        }
    }

    private fun deleteSwapJournal() {
        if (swapJournalFile.exists()) {
            require(swapJournalFile.delete()) { "Nie można usunąć zakończonego dziennika transakcji workspace." }
            syncDirectoryBestEffort(root)
        }
    }

    private fun syncDirectoryBestEffort(directory: File) {
        // Directory fsync is not supported by every Android filesystem/provider; file contents
        // and the journal itself are still fsynced. Never turn a committed operation into failure
        // solely because the platform refuses opening a directory channel.
        runCatching {
            java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /**
     * Recovers a pending transaction before exposing workspace state. A complete current pair
     * is accepted only when its manifest hash matches the journal and every file verifies.
     * Otherwise the previous pair is restored. A corrupt journal fails closed.
     */
    private fun recoverInterruptedSwap() {
        if (!root.isDirectory) return
        if (swapJournalFile.exists()) {
            val journal = try {
                readSwapJournal()
            } catch (error: Throwable) {
                throw IllegalStateException("Dziennik transakcji workspace jest uszkodzony; dostęp do projektu zablokowano.", error)
            }
            val old = File(root, ".previous-${journal.swapId}")
            val oldManifest = File(root, ".previous-${journal.swapId}.manifest")
            val currentIsCommitted = runCatching {
                currentDir.isDirectory && currentManifest.isFile &&
                    sha256(currentManifest) == journal.expectedManifestSha256 &&
                    (readManifest(currentManifest)?.let { verifyTreeAgainstManifest(currentDir, it); true } == true)
            }.getOrDefault(false)

            if (currentIsCommitted) {
                // The new pair reached the commit point; recreate the host receipt if the process
                // died after installing current/manifest but before publishing the receipt.
                ensurePatchReceipt(journal, rolledBack = false)
                old.deleteRecursively()
                oldManifest.delete()
            } else if (journal.hadCurrent) {
                val originalPairStillIntact = runCatching {
                    currentDir.isDirectory && currentManifest.isFile &&
                        (readManifest(currentManifest)?.let { verifyTreeAgainstManifest(currentDir, it); true } == true)
                }.getOrDefault(false)
                if (originalPairStillIntact && !old.exists()) {
                    // Death/failure after intent fsync but before the first rename.
                    // The original workspace never moved; persist a negative receipt for the host.
                    ensurePatchReceipt(journal, rolledBack = true)
                    deleteSwapJournal()
                    root.listFiles()?.filter { it.name.startsWith(".workspace.swap.") && it.name.endsWith(".tmp") }
                        ?.forEach { it.delete() }
                    return
                }
                require(old.isDirectory) {
                    "Nie można odzyskać poprzedniego workspace: brak katalogu poprzedniej transakcji."
                }
                currentDir.deleteRecursively()
                if (oldManifest.exists()) {
                    currentManifest.delete()
                    require(oldManifest.renameTo(currentManifest)) { "Nie można przywrócić poprzedniego manifestu workspace." }
                } else {
                    require(currentManifest.isFile && readManifest(currentManifest) != null) {
                        "Nie można odzyskać poprzedniego manifestu workspace."
                    }
                }
                require(old.renameTo(currentDir)) { "Nie można przywrócić poprzedniego katalogu workspace." }
                val restored = readManifest(currentManifest) ?: error("Odzyskany manifest workspace jest nieprawidłowy.")
                verifyTreeAgainstManifest(currentDir, restored)
                ensurePatchReceipt(journal, rolledBack = true)
            } else {
                // First import had no prior workspace: incomplete new data is discarded.
                currentDir.deleteRecursively()
                currentManifest.delete()
            }
            deleteSwapJournal()
            cleanupInterruptedStaging()
            return
        }

        // Compatibility recovery for workspaces created by versions before the journal existed.
        val previousDirs = root.listFiles()?.filter { it.isDirectory && it.name.startsWith(".previous-") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        if (!currentDir.exists() || !currentManifest.exists()) {
            val recoverable = previousDirs.firstOrNull { dir -> File(root, "${dir.name}.manifest").isFile }
            if (recoverable != null) {
                currentDir.deleteRecursively()
                currentManifest.delete()
                if (recoverable.renameTo(currentDir)) {
                    require(File(root, "${recoverable.name}.manifest").renameTo(currentManifest)) {
                        "Nie można przywrócić manifestu poprzedniego workspace."
                    }
                    val restored = readManifest(currentManifest) ?: error("Poprzedni manifest workspace jest uszkodzony.")
                    verifyTreeAgainstManifest(currentDir, restored)
                }
            }
        }
        if (currentDir.isDirectory && currentManifest.isFile) {
            previousDirs.forEach { it.deleteRecursively() }
            root.listFiles()?.filter { it.isFile && it.name.startsWith(".previous-") && it.name.endsWith(".manifest") }
                ?.forEach { it.delete() }
            cleanupInterruptedStaging()
        }
    }

    private fun cleanupInterruptedStaging() {
        // Do not discard potentially useful transaction evidence unless the active workspace
        // is independently verified. File existence alone is not a commit signal.
        val currentIsVerified = runCatching {
            currentDir.isDirectory && currentManifest.isFile &&
                (readManifest(currentManifest)?.let { verifyTreeAgainstManifest(currentDir, it); true } == true)
        }.getOrDefault(false)
        if (!currentIsVerified) return

        root.listFiles()?.filter { file ->
            file.name.startsWith(".staging-") || file.name.startsWith(".patch-staging-") ||
                file.name.startsWith(".patch-recovery-") ||
                ((file.name.startsWith(".workspace.swap.") || file.name.startsWith(".workspace.patch.receipt.")) && file.name.endsWith(".tmp"))
        }?.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
        root.listFiles()?.filter { file ->
            file.isFile && file.name.endsWith(".manifest") &&
                (file.name.startsWith(".staging-") || file.name.startsWith(".patch-staging-"))
        }?.forEach { it.delete() }

        // A committed current pair is the only safe authority for pruning abandoned backups.
        // This also handles a crash after the swap journal was removed but before all previous
        // directories/manifests could be deleted.
        root.listFiles()?.filter { file ->
            file.name.startsWith(".previous-") &&
                (file.isDirectory || (file.isFile && file.name.endsWith(".manifest")))
        }?.forEach { file ->
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) file.delete()
            else if (file.isDirectory) file.deleteRecursively()
            else file.delete()
        }
    }


    private fun pruneSnapshots(preserveSnapshotId: String? = null) {
        val dirs = snapshotsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()
        val keep = dirs.take(MAX_SNAPSHOTS).toMutableList()
        val protectedIds = mutableSetOf<String>()
        preserveSnapshotId?.let { protectedIds += it }
        if (patchReceiptFile.exists()) {
            val receipt = runCatching { readPatchReceiptFile() }.getOrElse { return }
            receipt?.snapshotId?.let { protectedIds += it }
        }
        protectedIds.forEach { id ->
            val preserved = dirs.firstOrNull { it.name == id }
            if (preserved != null && keep.none { it.name == preserved.name }) {
                if (keep.isNotEmpty()) keep.removeAt(keep.lastIndex)
                keep += preserved
            }
        }
        dirs.filterNot { candidate -> keep.any { it.name == candidate.name } }
            .forEach { it.deleteRecursively() }
    }

    private fun writeManifest(file: File, info: WorkspaceInfo) {
        val parent = file.parentFile ?: error("Manifest workspace nie ma katalogu nadrzędnego.")
        require(parent.isDirectory) { "Katalog manifestu workspace nie istnieje." }
        require(!file.exists()) { "Manifest docelowy już istnieje; zapis bez jawnej transakcji jest zabroniony." }
        val temp = File(parent, ".${file.name}.${UUID.randomUUID()}.tmp")
        publishWorkspaceDurableFile(
            temp = temp,
            target = file,
            replaceExisting = false,
            writePayload = { out ->
                out.writeInt(MANIFEST_MAGIC)
                out.writeInt(MANIFEST_VERSION)
                out.writeUTF(info.archiveName)
                out.writeLong(info.importedAt)
                out.writeInt(info.fileCount)
                out.writeLong(info.totalBytes)
                info.files.forEach { record ->
                    out.writeUTF(record.path)
                    out.writeLong(record.sizeBytes)
                    out.writeUTF(record.sha256)
                }
            },
            syncDirectory = ::syncDirectoryBestEffort
        )
    }

    private fun readManifest(file: File): WorkspaceInfo? = runCatching {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == MANIFEST_MAGIC && input.readInt() == MANIFEST_VERSION)
            val name = input.readUTF()
            val time = input.readLong()
            val count = input.readInt()
            val total = input.readLong()
            require(count in 1..MAX_ENTRIES && total in 0..MAX_TOTAL_UNPACKED_BYTES)
            val records = (0 until count).map {
                val path = input.readUTF()
                val size = input.readLong()
                val hash = input.readUTF()
                require(normalizeEntryPath(path, false) == path && size in 0..MAX_SINGLE_FILE_BYTES && hash.matches(Regex("[0-9a-f]{64}")))
                require(path.split('/').none { it.lowercase(java.util.Locale.ROOT) in excludedDirectories || excludedFile(it) })
                FileRecord(path, size, hash)
            }
            require(records.map { it.path.lowercase(java.util.Locale.ROOT) }.toSet().size == records.size)
            require(records.sumOf { it.sizeBytes } == total)
            WorkspaceInfo(name, time, count, total, records.sortedBy { it.path })
        }
    }.getOrNull()

    private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) account(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) account(read.toLong())
            return read
        }
        private fun account(amount: Long) {
            count += amount
            require(count <= maxBytes) { "Archiwum ZIP przekracza limit 40 MB." }
        }
    }
}
