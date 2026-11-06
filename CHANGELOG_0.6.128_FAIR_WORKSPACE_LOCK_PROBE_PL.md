# CHANGELOG — AI MASTER PRO 0.6.128

## Fairness correction for non-blocking workspace lock probes

### Problem
`ProjectWorkspaceOperationLock` was constructed as `ReentrantLock(true)`, but `tryWithLock()` used the untimed `tryLock()` overload. Java's `ReentrantLock` contract permits untimed `tryLock()` to barge even when the lock is fair. Therefore the fair constructor alone did not guarantee that queued workspace callers retained priority over repeated UI probes.

### Fix
- `tryWithLock()` now uses `lock.tryLock(0L, TimeUnit.NANOSECONDS)`. The timed zero-wait overload honors the fair queue without waiting for the lock.
- An interrupted caller receives `null`, the protected operation is not run, and the interrupt flag is restored.
- Reentrant acquisition by the owning thread remains supported.

### Regression protection
- Added a host runtime test proving a pre-interrupted caller fails closed and retains its interrupt flag.
- Added source guard for `ReentrantLock(true)`, timed zero-wait `tryLock`, and interrupt restoration.
- Workspace lock harness: 10/10 PASS.
- Full host regression: 175 executed cases PASS; compile shim and all source guards PASS.

### Deliberately deferred
ZIP extraction/import still runs inside the workspace lock. It is not safe to move only extraction outside the lock without a staged-import protocol. A future implementation must separate prepare and commit, bind the prepared tree to a unique immutable import ID, validate it before commit, re-check current workspace identity/integrity under the gate, and make cleanup/process-death recovery explicit. No speculative refactor was made here.

### Limits
Android/Gradle/Robolectric/SharedPreferences/device/process-death/APK remain NOT RUN. Preflight is BLOCKED by missing wrapper JAR, system Gradle and Android SDK, plus unavailable DNS for `services.gradle.org`. App version remains `0.6.54 / code 80`; Companion Transport remains BLOCKED.
