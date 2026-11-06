package pl.aimasterpro.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ExecutorBundleExportTest {
    private fun sourceZip(content: String): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("README.md")); zip.write(content.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("app/build.gradle.kts")); zip.write("plugins {}".toByteArray()); zip.closeEntry()
        }
        bytes.toByteArray()
    }

    @Test fun exportsOnlyVerifiedSnapshotWithManifestAndExactPayload() {
        val root = createTempDirectory("aimaster-workspace-").toFile()
        val output = createTempDirectory("aimaster-export-").toFile()
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(sourceZip("v1")), "one.zip", 1_800_000_000_000L)
            store.importZip(ByteArrayInputStream(sourceZip("v2")), "two.zip", 1_800_000_000_100L)
            val snapshot = store.snapshots().first()
            val target = File(output, "verified.bundle.zip")
            val result = store.exportSnapshotBundle(snapshot.id, target, 1_800_000_000_200L)
            assertTrue(target.isFile)
            assertEquals(snapshot.id, result.snapshotId)
            assertEquals(2, result.fileCount)
            val names = mutableSetOf<String>()
            ZipInputStream(target.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names += entry.name
                    if (entry.name == "manifest.bin") {
                        val decoded = ExecutorProtocol.decodeManifest(zip.readBytes())
                        assertEquals(result.manifestDigest, decoded.digest)
                    }
                }
            }
            assertEquals(setOf("manifest.bin", "project/README.md", "project/app/build.gradle.kts"), names)
        } finally {
            root.deleteRecursively(); output.deleteRecursively()
        }
    }

    @Test fun refusesModifiedSnapshotAndDoesNotCreateOutput() {
        val root = createTempDirectory("aimaster-workspace-").toFile()
        val output = createTempDirectory("aimaster-export-").toFile()
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(sourceZip("v1")), "one.zip", 1_800_000_000_000L)
            store.importZip(ByteArrayInputStream(sourceZip("v2")), "two.zip", 1_800_000_000_100L)
            val snapshot = store.snapshots().first()
            File(root, "snapshots/${snapshot.id}/project/README.md").writeText("tampered")
            val target = File(output, "must-not-exist.zip")
            assertThrows(IllegalArgumentException::class.java) {
                store.exportSnapshotBundle(snapshot.id, target, 1_800_000_000_200L)
            }
            assertFalse(target.exists())
        } finally {
            root.deleteRecursively(); output.deleteRecursively()
        }
    }

    @Test fun exportBlocksInlineSecretAndRedactsValueFromError() {
        val root = createTempDirectory("aimaster-workspace-").toFile()
        val output = createTempDirectory("aimaster-export-").toFile()
        val secret = "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        try {
            val store = ProjectWorkspaceStore(root)
            store.importZip(ByteArrayInputStream(sourceZip("api_key=$secret")), "one.zip", 1_800_000_000_000L)
            store.importZip(ByteArrayInputStream(sourceZip("safe")), "two.zip", 1_800_000_000_100L)
            val snapshot = store.snapshots().first()
            val target = File(output, "blocked.bundle.zip")
            val error = assertThrows(IllegalArgumentException::class.java) {
                store.exportSnapshotBundle(snapshot.id, target, 1_800_000_000_200L)
            }
            assertFalse(error.message.orEmpty().contains(secret))
            assertFalse(target.exists())
        } finally {
            root.deleteRecursively(); output.deleteRecursively()
        }
    }

    @Test fun scannerAvoidsGenericTokenClassNamesButBlocksCredentialFilenames() {
        val dir = createTempDirectory("aimaster-scan-").toFile()
        try {
            val ordinary = File(dir, "TokenManager.kt").apply { writeText("class TokenManager") }
            val sensitive = File(dir, "app-secrets.yaml").apply { writeText("safe: true") }
            assertTrue(BundleContentScanner.scan("TokenManager.kt", ordinary).clean)
            assertFalse(BundleContentScanner.scan("app-secrets.yaml", sensitive).clean)
        } finally { dir.deleteRecursively() }
    }

    @Test fun scannerFindsUtf16SecretsAndRejectsOversizedFiles() {
        val dir = createTempDirectory("aimaster-scan-utf16-").toFile()
        try {
            val secret = "api_key=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
            val utf16 = File(dir, "settings.txt").apply { writeBytes(secret.toByteArray(Charsets.UTF_16LE)) }
            assertFalse(BundleContentScanner.scan("settings.txt", utf16).clean)
            val utf16Be = File(dir, "settings-be.txt").apply { writeBytes(secret.toByteArray(Charsets.UTF_16BE)) }
            assertFalse(BundleContentScanner.scan("settings-be.txt", utf16Be).clean)
            val oversized = File(dir, "oversized.txt").apply { writeBytes(ByteArray(8 * 1024 * 1024 + 1)) }
            assertFalse(BundleContentScanner.scan("oversized.txt", oversized).clean)
        } finally { dir.deleteRecursively() }
    }

    @Test fun binaryManifestRoundTripsAndRejectsTrailingBytes() {
        val manifest = ExecutorProtocol.createManifest("bundle-1", "workspace-1", "snapshot-1", 1_800_000_000_000L,
            listOf(ExecutorProtocol.BundleFile("README.md", 3, "a".repeat(64))))
        val encoded = ExecutorProtocol.encodeManifest(manifest)
        assertEquals(manifest, ExecutorProtocol.decodeManifest(encoded))
        assertThrows(IllegalArgumentException::class.java) {
            ExecutorProtocol.decodeManifest(encoded + byteArrayOf(1))
        }
    }
}
