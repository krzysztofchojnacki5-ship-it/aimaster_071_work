import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

private fun expect(value: Boolean, message: String) { check(value) { message } }
private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

/** Host filesystem prototype only. Production Android integration is intentionally not wired. */
private class JournalFile(private val directory: Path) {
    private val target = directory.resolve("import.journal")
    private val temp = directory.resolve("import.journal.tmp")
    data class Entry(val id: String, val stage: String)

    fun write(entry: Entry, beforeRename: (() -> Unit)? = null) {
        require(entry.id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(entry.stage in setOf("PREPARED", "COMMITTING", "SWAPPED_AWAITING_EVIDENCE", "COMMITTED", "ABORTED"))
        val payload = "format=1\nid=${entry.id}\nstage=${entry.stage}\n".toByteArray(Charsets.UTF_8)
        val bytes = payload + "sha256=${sha256(payload)}\n".toByteArray(Charsets.UTF_8)
        FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            var buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        beforeRename?.invoke()
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("atomic journal replacement unavailable; refusing non-atomic fallback", e)
        }
        // Best-effort directory metadata flush on host filesystems that permit opening directories.
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }

    fun read(): Entry? {
        if (!Files.exists(target)) return null
        val bytes = Files.readAllBytes(target)
        require(bytes.size in 1..4096) { "journal size invalid" }
        val text = bytes.toString(Charsets.UTF_8)
        val marker = "sha256="
        val checksumLine = text.lineSequence().lastOrNull { it.startsWith(marker) } ?: error("journal checksum missing")
        val payload = text.substringBeforeLast(marker).toByteArray(Charsets.UTF_8)
        require(checksumLine.removePrefix(marker).trim() == sha256(payload)) { "journal checksum mismatch" }
        val lines = payload.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        require(lines.size == 3 && lines[0] == "format=1") { "unsupported or malformed journal" }
        val id = lines[1].removePrefix("id=").takeIf { lines[1].startsWith("id=") } ?: error("journal id missing")
        val stage = lines[2].removePrefix("stage=").takeIf { lines[2].startsWith("stage=") } ?: error("journal stage missing")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "journal id invalid" }
        require(stage in setOf("PREPARED", "COMMITTING", "SWAPPED_AWAITING_EVIDENCE", "COMMITTED", "ABORTED")) { "unknown journal stage" }
        return Entry(id, stage)
    }
    fun targetPath() = target
    fun tempPath() = temp
}

fun main() {
    var passed = 0
    fun test(name: String, block: () -> Unit) {
        block(); passed++; println("PASS $name")
    }
    test("round trip writes and reads versioned journal") {
        val dir = Files.createTempDirectory("aimaster-journal-roundtrip-")
        try { val j = JournalFile(dir); j.write(JournalFile.Entry("job_1", "PREPARED")); expect(j.read() == JournalFile.Entry("job_1", "PREPARED"), "round trip mismatch") } finally { dir.toFile().deleteRecursively() }
    }
    test("atomic replacement exposes complete new record") {
        val dir = Files.createTempDirectory("aimaster-journal-atomic-")
        try { val j = JournalFile(dir); j.write(JournalFile.Entry("job_1", "PREPARED")); j.write(JournalFile.Entry("job_1", "COMMITTING")); expect(j.read()?.stage == "COMMITTING", "new record not visible"); expect(!Files.exists(j.tempPath()), "temp remained after successful rename") } finally { dir.toFile().deleteRecursively() }
    }
    test("failure before rename preserves previous committed journal") {
        val dir = Files.createTempDirectory("aimaster-journal-fault-")
        try { val j = JournalFile(dir); j.write(JournalFile.Entry("job_1", "PREPARED")); val failed = runCatching { j.write(JournalFile.Entry("job_1", "COMMITTING")) { throw IOException("injected crash before rename") } }; expect(failed.isFailure, "fault injection did not fail"); expect(j.read()?.stage == "PREPARED", "old journal was not preserved") } finally { dir.toFile().deleteRecursively() }
    }
    test("checksum corruption fails closed") {
        val dir = Files.createTempDirectory("aimaster-journal-corrupt-")
        try { val j = JournalFile(dir); j.write(JournalFile.Entry("job_1", "PREPARED")); val b = Files.readAllBytes(j.targetPath()); b[ b.indexOf('P'.code.toByte()) ] = 'X'.code.toByte(); Files.write(j.targetPath(), b); expect(runCatching { j.read() }.isFailure, "corrupt journal accepted") } finally { dir.toFile().deleteRecursively() }
    }
    test("truncated journal fails closed") {
        val dir = Files.createTempDirectory("aimaster-journal-truncated-")
        try { val j = JournalFile(dir); Files.write(j.targetPath(), "format=1\nid=x\n".toByteArray()); expect(runCatching { j.read() }.isFailure, "truncated journal accepted") } finally { dir.toFile().deleteRecursively() }
    }
    test("unknown format version fails closed") {
        val dir = Files.createTempDirectory("aimaster-journal-version-")
        try { val j = JournalFile(dir); val payload = "format=2\nid=x\nstage=PREPARED\n".toByteArray(); val bytes = payload + "sha256=${sha256(payload)}\n".toByteArray(); Files.write(j.targetPath(), bytes); expect(runCatching { j.read() }.isFailure, "unknown format accepted") } finally { dir.toFile().deleteRecursively() }
    }
    test("unknown state fails closed") {
        val dir = Files.createTempDirectory("aimaster-journal-state-")
        try { val j = JournalFile(dir); val payload = "format=1\nid=x\nstage=MAGIC\n".toByteArray(); Files.write(j.targetPath(), payload + "sha256=${sha256(payload)}\n".toByteArray()); expect(runCatching { j.read() }.isFailure, "unknown stage accepted") } finally { dir.toFile().deleteRecursively() }
    }
    test("unsafe identifier is rejected before write") {
        val dir = Files.createTempDirectory("aimaster-journal-id-")
        try { val j = JournalFile(dir); expect(runCatching { j.write(JournalFile.Entry("../escape", "PREPARED")) }.isFailure, "unsafe id accepted"); expect(!Files.exists(j.targetPath()), "unsafe journal was written") } finally { dir.toFile().deleteRecursively() }
    }
    println("STAGED_IMPORT_JOURNAL_FILESYSTEM: $passed/8 PASS (host filesystem prototype; Android durability NOT CLAIMED)")
}
