from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = (root / "app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceStore.kt").read_text()
read_start = source.index("private fun readSwapJournal(): SwapJournal")
read_end = source.index("private fun ensurePatchReceipt(", read_start)
reader = source[read_start:read_end]

assert "MAX_SWAP_JOURNAL_BYTES = 16L * 1024L" in source, "Production swap journal must have an explicit bounded size"
assert "!Files.isSymbolicLink(swapJournalFile.toPath())" in reader, "Swap journal symlink must be rejected"
assert "swapJournalFile.isFile" in reader, "Swap journal must be a regular file"
assert "swapJournalFile.canonicalFile.parentFile == root.canonicalFile" in reader, "Swap journal must remain directly inside private root"
assert "swapJournalFile.length() in 1L..MAX_SWAP_JOURNAL_BYTES" in reader, "Journal size must be checked before opening/parsing"
assert reader.index("swapJournalFile.length()") < reader.index("FileInputStream(swapJournalFile)"), "Size guard must precede file parsing"
assert "version in 1..SWAP_JOURNAL_VERSION" in reader, "Legacy v1 and current format versions must remain explicitly bounded"
assert "input.read() == -1" in reader, "Trailing bytes must be rejected"

# Confirm tests exercise the production store's public recovery entry point.
tests = (root / "app/src/test/java/pl/aimasterpro/app/core/ProjectWorkspaceStoreTest.kt").read_text()
for marker in (
    "rejectsOversizedSwapJournalBeforeParsing",
    "rejectsSwapJournalWithTrailingBytes",
    "recoversLegacyVersionOneSwapJournalWithoutPatchMetadata",
    "committedSwapRecoveryPrunesOrphanedPreviousArtifactsOnlyAfterCurrentVerification",
):
    assert marker in tests, f"Missing production-store regression test: {marker}"
    assert "ProjectWorkspaceStore(root).currentInfo()" in tests, "Tests must trigger production recovery through currentInfo()"
print("PASS production swap-journal bounds, path, version, EOF and regression-test source guard")

cleanup_start = source.index("private fun cleanupInterruptedStaging()")
cleanup_end = source.index("private fun pruneSnapshots", cleanup_start)
cleanup = source[cleanup_start:cleanup_end]
assert "currentIsVerified" in cleanup, "Previous artifacts may only be pruned after current workspace verification"
assert "verifyTreeAgainstManifest(currentDir, it)" in cleanup, "Cleanup must verify current tree against manifest"
assert "Files.isSymbolicLink(path)" in cleanup, "Cleanup must unlink symlink artifacts without traversing them"
assert 'file.name.startsWith(".previous-")' in cleanup, "Verified recovery must clean orphaned previous artifacts"
print("PASS verified-current orphan previous cleanup guard")

cleanup_start = source.index("private fun cleanupInterruptedStaging()")
cleanup_end = source.index("private fun pruneSnapshots", cleanup_start)
cleanup = source[cleanup_start:cleanup_end]
assert cleanup.index("if (!currentIsVerified) return") < cleanup.index('file.name.startsWith(".staging-")'),     "Transaction temp/staging evidence must not be deleted before current workspace verification"
assert "verifyTreeAgainstManifest(currentDir, it)" in cleanup, "Cleanup must verify full current tree before deleting temp evidence"
assert "interruptedTempEvidenceIsPreservedWhenCurrentWorkspaceIsUnverified" in tests,     "Missing JUnit regression test for preserving temp evidence when current is corrupt"
print("PASS fail-closed interrupted-temp cleanup gate")

# Both production writers must delegate to the same fault-injectable durable publisher.
for writer_name, next_name in (("private fun writeSwapJournal(", "private fun readSwapJournal()"),
                               ("private fun writePatchReceipt(", "private fun deleteSwapJournal()")):
    start = source.index(writer_name)
    end = source.index(next_name, start)
    writer = source[start:end]
    assert "publishWorkspaceDurableFile(" in writer, f"{writer_name} must use the shared durable publisher"
    assert "syncDirectory = ::syncDirectoryBestEffort" in writer, f"{writer_name} must preserve best-effort directory sync"
assert "var ownsTemp = false" in source and "cleanupOwnedTemporaryFile(temp, ownsTemp)" in source
assert "Files.deleteIfExists(temp.toPath())" in source, "Owned temp cleanup must unlink the path entry without following symlinks"
publisher_start = source.index("internal fun publishWorkspaceDurableFile(")
publisher_end = source.index("/**", publisher_start)
publisher = source[publisher_start:publisher_end]
assert "temp.createNewFile()" in publisher, "Publisher must claim temp ownership with CREATE_NEW semantics"
assert "moveAtomically(temp.toPath(), target.toPath(), replaceExisting)" in publisher, "Publisher must atomically publish the completed payload"
assert "runCatching { syncDirectory(" in publisher, "Directory fsync must remain best-effort"
assert "output.flush()" in publisher and "stream.fd.sync()" in publisher, "Publisher must flush buffered payload and fsync the file before move"
assert "flushAndSync(output, stream)" in publisher, "Flush/fsync boundary must be fault-injectable"
assert "cleanupOwnedTemporaryFile(temp, ownsTemp)" in publisher, "Publisher must clean only an owned temp path"
assert "Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE" in publisher, "Publication must require ATOMIC_MOVE"
assert "Files.move(source, destination)" not in publisher, "No non-atomic fallback may be introduced"
print("PASS shared durable publisher, ownership, atomic-only move and best-effort directory-sync guards")
