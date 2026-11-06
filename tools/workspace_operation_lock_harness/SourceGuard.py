from pathlib import Path

root = Path(__file__).resolve().parents[2]
runner = (root / "app/src/main/java/pl/aimasterpro/app/core/ProjectTestRunner.kt").read_text()
public_start = runner.index("fun verifyAppliedChange(")
public_end = runner.index("private fun verifyAppliedChangeLocked(", public_start)
public_boundary = runner[public_start:public_end]
assert "= ProjectWorkspaceOperationLock.withLock {" in public_boundary, \
    "ProjectTestRunner public entry point must acquire the global workspace gate"
assert "verifyAppliedChangeLocked(snapshotId, expectedChangedPaths, taskId, chatId)" in public_boundary, \
    "Public verification boundary must delegate all reads to the locked implementation"
assert "private fun verifyAppliedChangeLocked(" in runner, \
    "Unlocked implementation must remain private"
print("PASS ProjectTestRunner public boundary is globally lock-protected")

from pathlib import Path

lock_source = (Path(__file__).resolve().parents[2] / "app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceOperationLock.kt").read_text()
assert "ReentrantLock(true)" in lock_source, "Workspace lock must retain fair mode"
assert "lock.tryLock(0L, TimeUnit.NANOSECONDS)" in lock_source, \
    "Non-blocking probe must use timed tryLock so fair queue is honored"
assert "Thread.currentThread().interrupt()" in lock_source, \
    "Interrupted probe must restore the caller interrupt flag"
print("PASS fair non-blocking acquisition and interrupt preservation source guard")
