import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Host-side protocol harness. It mirrors the marker I/O ordering; it does not execute Android store code. */
private const val CONTENT = "AI_MASTER_COMPANION_TRUST_LOCK_V1"

private fun syncDirectory(directory: File) {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}

private fun publish(directory: File, failAt: String?) {
    val target = File(directory, "companion_trust_unavailable.lock")
    val temp = Files.createTempFile(directory.toPath(), "companion_trust_unavailable.lock.", ".tmp")
    try {
        FileOutputStream(temp.toFile()).use {
            it.write(CONTENT.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (failAt == "before_move") error("injected")
        Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        if (failAt == "after_move") error("injected")
        if (failAt == "before_fsync") error("injected")
        syncDirectory(directory)
        if (failAt == "after_fsync") error("injected")
    } finally {
        Files.deleteIfExists(temp)
    }
}

private fun delete(directory: File, failAt: String?) {
    val target = File(directory, "companion_trust_unavailable.lock")
    if (failAt == "before_delete") error("injected")
    Files.deleteIfExists(target.toPath())
    if (failAt == "after_delete") error("injected")
    if (failAt == "before_fsync") error("injected")
    syncDirectory(directory)
    if (failAt == "after_fsync") error("injected")
}

fun main() {
    val directory = Files.createTempDirectory("companion-fault-matrix-").toFile()
    var passed = 0
    try {
        val publishCases = listOf("before_move" to false, "after_move" to true, "before_fsync" to true, "after_fsync" to true)
        repeat(25) {
            publishCases.forEach { (point, expectedMarker) ->
                File(directory, "companion_trust_unavailable.lock").delete()
                val failure = runCatching { publish(directory, point) }.exceptionOrNull()
                check(failure != null) { "Expected injected publication failure at $point" }
                check(File(directory, "companion_trust_unavailable.lock").isFile == expectedMarker) {
                    "Wrong publication state at $point"
                }
                check(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") }) { "Temporary file leaked" }
                passed++
            }
        }
        val deleteCases = listOf("before_delete" to true, "after_delete" to false, "before_fsync" to false, "after_fsync" to false)
        repeat(25) {
            deleteCases.forEach { (point, expectedMarker) ->
                File(directory, "companion_trust_unavailable.lock").writeText(CONTENT)
                val failure = runCatching { delete(directory, point) }.exceptionOrNull()
                check(failure != null) { "Expected injected deletion failure at $point" }
                check(File(directory, "companion_trust_unavailable.lock").isFile == expectedMarker) {
                    "Wrong deletion state at $point"
                }
                passed++
            }
        }
        println("$passed/200 injected marker crash-boundary cases PASS (host NIO protocol harness)")
    } finally {
        directory.deleteRecursively()
    }
}
