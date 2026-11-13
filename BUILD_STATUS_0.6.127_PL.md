# BUILD STATUS — AI MASTER PRO 0.6.127-SOURCE

- Source base: `0.6.126-SOURCE`; retrieved from the user's Library, SHA-256 verified before extraction, and ZIP integrity checked.
- App version unchanged: `0.6.54`, `versionCode=80`.
- Deliverable: source ZIP only. No APK was generated.

## Production change — serialize chat-switch invalidation with workspace commits

1. **Race identified:** `openChat()`, `newChat()` and `newAssistantChat()` run under the `MainActivity` monitor and call `invalidateActiveEvidenceScopeForChatSwitch()`. Before this change, that helper invalidated the durable active pointer without acquiring `ProjectWorkspaceOperationLock`.
2. **Failure window:** startup receipt recovery commits activation under the workspace lock but uses the intentionally monitor-free `isCurrentChat()` callback. Chat switching could therefore invalidate the pointer concurrently with recovery's activation/acknowledgement sequence. Chat-scope validation prevented cross-chat evidence use, but the persisted pointer mutation itself was not serialized with the recovery commit.
3. **Fix:** `invalidateActiveEvidenceScopeForChatSwitch()` now acquires `ProjectWorkspaceOperationLock.withLock` around `HostActiveChangeSetStore.invalidate()`. The method remains `@Synchronized`, so the documented lock order is `MainActivity monitor -> workspace gate`.
4. **Deadlock boundary:** callbacks executed while the workspace gate is held must remain monitor-free with respect to `MainActivity`. `isCurrentChat()` remains a lock-free volatile visibility check; the recovery path does not acquire the Activity monitor while holding the workspace gate.
5. **Permanent guards/tests:** workspace lock harness now verifies serialization between recovery commit and chat-switch invalidation (9/9). `WorkspaceCallSiteGuard.py` asserts the synchronized chat-switch helper owns the global lock and that the recovery chat check remains monitor-free. The recovery concurrency harness's static boundary was updated to locate the method by signature rather than comment wording.

## Executed host checks

| Suite | Result |
|---|---:|
| ChangeSet storage fault matrix | 21/21 PASS |
| ChangeSet + HostActive compile shim (`-Werror`) | PASS |
| Patch receipt commit gate | 9/9 PASS |
| Patch receipt recovery coordinator | 19/19 PASS |
| Patch receipt recovery policy | 11/11 PASS |
| Patch receipt protocol | 8/8 PASS |
| Recovery concurrency | 3/3 PASS |
| Workspace operation lock | 9/9 PASS |
| Workspace transaction recovery | 12/12 PASS |
| Verification resolver | 73/73 PASS |
| Filesystem recovery integration | 9/9 PASS |
| ProjectTestRunner public-boundary source guard | PASS |
| MainActivity lock-order / atomicity guards | PASS |
| 39-call-site workspace source guard + chat-switch lock guard | PASS |
| **Total executed host cases** | **174 PASS** |

All 11 harness scripts were run individually. During the first pass, the recovery concurrency source guard still searched for the previous documentation-comment wording and failed after the method comment was expanded. The guard was corrected to anchor on the method signature, then the complete concurrency harness passed 3/3. A combined shell batch exceeded its execution window; remaining suites were run individually. No unexecuted test is counted.

## Lock-scope audit observations

- Workspace critical sections perform local filesystem/manifest/snapshot work and bounded preference/ledger operations; no network/provider model call or UI dispatch was found inside the audited workspace lock regions.
- Import stream consumption remains inside the lock because the current importer performs extraction and workspace swap as one store operation. Moving extraction outside the lock would require a separate staged-import API and a second validation/identity fence; not changed in this iteration.
- `ProjectWorkspaceOperationLock` remains process-wide and reentrant, not an OS-level or cross-process file lock.

## Android environment and unexecuted checks

Preflight rerun:
- Java 21: PASS.
- `gradle/wrapper/gradle-wrapper.jar`: missing.
- System Gradle: missing.
- Android SDK / `ANDROID_HOME` / `ANDROID_SDK_ROOT`: missing.
- DNS resolution for `services.gradle.org`: unavailable.

Gradle, JUnit/Robolectric, Android SharedPreferences, Android compilation, APK generation, emulator/device tests, and real process-death/process-recreation tests are **NOT RUN**. Host JVM harnesses and source guards do not prove Android runtime or disk durability.

## Safety and release boundaries

- Companion Transport remains **BLOCKED**; no transport was enabled.
- App version remains `0.6.54 / code 80`.
- No APK generated.
- The Activity-monitor -> workspace-gate lock order is intentional. Never add a path that acquires the Activity monitor while already holding the workspace gate.
