# CHANGELOG 0.6.122 — ambiguous pointer quarantine + startup evidence gate

## Problem
0.6.121 added best-effort compensation after `SharedPreferences.commit()` returns false or throws. If the attempted commit and the compensating commit both fail, the in-memory map can still expose the attempted pointer or a previous pointer. Neither view can be trusted merely because it can be read. A process restart adds another boundary: the durable workspace patch receipt may still be awaiting ledger/pointer reconciliation.

## Production change
- `HostActiveChangeSetStore` now keeps an in-process quarantine for the normalized identity of an active record whose write or removal was not confirmed.
- On failed activation it quarantines both the attempted record and any valid record currently visible through the storage adapter. `readActive()` returns `INVALID` and no active record for a quarantined identity.
- A confirmed activation or invalidation clears the matching quarantine. Failed invalidation remains fail-closed.
- `MainActivity.buildMasterEvidenceContext()` checks `hasPendingWorkspaceTransaction()` before reading the active pointer. If a swap journal/patch receipt is pending, or the check itself fails, it refuses to select evidence. It uses the read-only existence check rather than `readPendingPatchReceipt()`, avoiding workspace recovery side effects outside the shared operation lock.
- Startup reconciliation remains responsible for resolving the receipt under `ProjectWorkspaceOperationLock`; the commit gate only acknowledges after its activation/invalidation conditions are met.

## Tests
- Storage fault matrix: 17/17 PASS. New cases cover mutate-then-false active pointer, double-false write+compensation, quarantine of the prior visible pointer, and confirmed retry.
- ChangeSet/HostActive compile shim: PASS (`kotlinc -Werror`).
- Commit gate 9/9; coordinator 19/19; policy 11/11; receipt protocol 8/8; recovery concurrency 3/3; workspace lock 5/5; workspace transaction recovery 12/12; verification resolver 73/73; filesystem integration 9/9.
- Total executed host cases: 166 PASS. MainActivity static lock-order and pending-transaction evidence guard PASS.
- Added one Robolectric test for quarantine + retry: ADDED / NOT RUN.

## Not run / limits
Gradle, Robolectric, real Android SharedPreferences, Android compile, APK, emulator/device and actual process-death tests NOT RUN. Fake SharedPreferences only models process-local mutation and false return; it does not prove disk persistence. MainActivity integration is source-guarded, not Android-compiled.

## Versioning
Application version remains `0.6.54` / `versionCode 80`. Companion transport remains BLOCKED.
