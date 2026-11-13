# CHANGELOG — AI MASTER PRO 0.6.126

## Complete workspace call-site audit and permanent lock guard

### Audited
- Enumerated all 39 production `ProjectWorkspaceStore` API invocations in `MainActivity.kt`.
- Confirmed 37 calls execute inside `ProjectWorkspaceOperationLock.withLock` or `tryWithLock`.
- Confirmed the remaining two callbacks (`currentWorkspaceIdentity` and `acknowledgePatchReceipt`) are invoked only through `PatchReceiptRecoveryCommitGate.commit()`, which owns the same global lock.
- Confirmed store construction remains centralized in MainActivity and `ProjectTestRunner.verifyAppliedChange()` owns its public lock boundary.
- Reviewed the remaining production references to workspace data types and pure analysis helpers; they do not introduce additional store I/O entry points.

### Added
- `tools/workspace_operation_lock_harness/WorkspaceCallSiteGuard.py`: delimiter-aware Kotlin source audit that ignores comments and quoted literals, inventories all known workspace API calls, and rejects unguarded or newly introduced call sites until explicitly reviewed.
- Integrated the new guard into `tools/workspace_operation_lock_harness/run.sh`.
- The guard verifies the recovery commit gate's own lock boundary and the ProjectTestRunner public boundary.

### Tests
- Full host regression: 173 executed cases PASS.
- Workspace operation lock harness: 8/8 PASS.
- New 39-call-site source guard: PASS.
- Compile shim (`-Werror`): PASS.
- Android preflight: BLOCKED; Gradle/Robolectric/Android/APK/device/process-death remain NOT RUN.

### Unchanged
- App version `0.6.54`, versionCode `80`.
- Companion Transport remains BLOCKED.
