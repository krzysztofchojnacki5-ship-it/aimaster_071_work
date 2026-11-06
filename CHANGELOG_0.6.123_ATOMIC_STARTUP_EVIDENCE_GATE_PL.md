# CHANGELOG 0.6.123 — atomic startup evidence gate

## Problem found in 0.6.122
The evidence path checked `hasPendingWorkspaceTransaction()` and then read the active ChangeSet pointer in separate operations. A patch/import/recovery operation could acquire the workspace lock and start after the pending-state check but before `readActive()`. This was a time-of-check/time-of-use race: the two individually correct checks did not describe one stable workspace state.

## Production change
- Added `HostActiveEvidenceStartupGate` with explicit `CLEAR`, `PENDING`, and `UNKNOWN` states.
- `PENDING` and `UNKNOWN` fail closed without invoking the active-pointer reader. `CLEAR` permits one lazy read; exceptions are converted to `INVALID`.
- `MainActivity.buildMasterEvidenceContext()` now acquires `ProjectWorkspaceOperationLock.tryWithLock` and, inside that same critical section, checks pending swap/receipt state, reads the workspace identity, and reads the active pointer.
- Lock contention is non-blocking and fails closed: no evidence is selected while another workspace operation owns the lock.
- Added a static integration guard to ensure the transaction probe and active-pointer read remain inside the same lock closure.

## Tests
- ChangeSet storage fault matrix: 21/21 PASS; four new host cases cover pending, unknown, clear, and active-read exception.
- ChangeSet/HostActive/startup-gate compile shim: PASS (`kotlinc -Werror`).
- Commit gate 9/9; coordinator 19/19; policy 11/11; protocol 8/8; concurrency 3/3; workspace operation lock 5/5; workspace transaction recovery 12/12; verification resolver 73/73; filesystem integration 9/9.
- Total: 170 executed host cases PASS; MainActivity atomic-lock static guard PASS.
- Four JUnit tests added for the startup gate: ADDED / NOT RUN.

## Not run / limits
Android preflight BLOCKED: wrapper JAR, system Gradle and Android SDK are absent; `services.gradle.org` DNS is unavailable. Gradle, Robolectric, real Android SharedPreferences, Android compile, APK, emulator/device and actual process-death tests NOT RUN. Host tests do not prove Android disk durability. Every future workspace mutation path must continue to honor the shared operation lock.

## Versioning
Application version remains `0.6.54` / `versionCode 80`. Companion transport remains BLOCKED.
