# CHANGELOG — 0.6.129: staged import protocol design

- Audited `ProjectWorkspaceStore.importZip()` and its `MainActivity` lock boundary.
- Added a formal two-phase prepare/commit specification for a future staged importer, including ownership tokens, immutable staging, commit-time tree/workspace revalidation, snapshots, journaled swap, evidence invalidation, replay protection, cleanup, and process-death recovery.
- Explicitly decided that a healthy workspace changed after prepare may still be replaced by an intentional import, but the **latest** workspace must be snapshotted at commit. A corrupt current workspace remains fail-closed.
- Added a 12-case executable host contract model and a production source guard.
- No production Kotlin behavior changed. ZIP extraction remains under the global workspace lock; the proposed contract is not yet wired into Android production code.
- Regression: 187 host cases PASS, plus compile-only shim and source guards. Android/Gradle/Robolectric/APK/device/process-death NOT RUN.
- App `0.6.54 / code 80`; Companion Transport BLOCKED.
