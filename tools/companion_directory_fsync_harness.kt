import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

fun syncDir(dir: java.nio.file.Path) = FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
fun main() {
  val dir = Files.createTempDirectory("companion-lock-fsync-")
  val target = dir.resolve("companion_trust_unavailable.lock")
  repeat(100) {
    val tmp = Files.createTempFile(dir, "companion_trust_unavailable.lock.", ".tmp")
    FileOutputStream(tmp.toFile()).use { out -> out.write("AI_MASTER_COMPANION_TRUST_LOCK_V1".toByteArray()); out.fd.sync() }
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    syncDir(dir)
    check(Files.readString(target) == "AI_MASTER_COMPANION_TRUST_LOCK_V1")
    check(Files.deleteIfExists(target))
    syncDir(dir)
    check(!Files.exists(target))
  }
  Files.delete(dir)
  println("100/100 directory-fsync publication/deletion cycles PASS")
}
