# CHANGELOG — AI MASTER PRO 0.6.124

## Workspace failure reconciliation and recovery diagnostic locking

### Fixed
- Closed a TOCTOU gap in the patch-apply failure path: pending workspace transaction detection, optional `REJECTED` ledger transition, and active ChangeSet pointer invalidation now execute under the shared `ProjectWorkspaceOperationLock`.
- A failed/unknown pending-transaction probe still prevents the ChangeSet from being marked `REJECTED`; the path remains fail-closed.
- Made the post-recovery diagnostic observation of workspace identity and active pointer atomic under one workspace lock on the recovery worker, avoiding mixed-generation log conclusions. A failed diagnostic is reported as unconfirmed, not misreported as a scope mismatch.

- Normal patch receipt acknowledgement now reacquires the workspace lock and checks that the current workspace identity still equals the identity produced by the patch. A concurrent import/new workspace generation therefore cannot be followed by a stale receipt deletion.

### Tests and guards
- Added two host concurrency cases to `workspace_operation_lock_harness`; the MainActivity static guard also verifies identity-fenced receipt acknowledgement.
- Extended `patch_recovery_concurrency_harness` static integration guard to verify the MainActivity failure path and post-recovery diagnostic lock coverage.
- Full host regression: 172 executed cases PASS; compile shim and static guards PASS.
- Android/Gradle/Robolectric/APK/device/process-death checks remain NOT RUN because the environment preflight is blocked.

### Unchanged
- App version `0.6.54`, versionCode `80`.
- Companion Transport remains BLOCKED.
