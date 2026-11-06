# CHANGELOG — AI MASTER PRO 0.6.127

## Chat-switch active-scope invalidation serialized with recovery commit

### Problem
`openChat()`, `newChat()` and `newAssistantChat()` serialize chat identity changes through the `MainActivity` monitor. Their shared helper invalidated the active ChangeSet pointer without the process-wide workspace lock. Startup receipt recovery, however, activates/invalidates the pointer and acknowledges the receipt under that workspace lock. Because recovery's chat predicate is intentionally monitor-free, the pointer invalidation itself could overlap the recovery commit sequence.

The exact-chat resolver still prevented a pointer for a different chat from authorizing evidence, but the persistent pointer mutation was not serialized with the recovery transaction.

### Fix
- `invalidateActiveEvidenceScopeForChatSwitch()` now wraps durable pointer invalidation in `ProjectWorkspaceOperationLock.withLock`.
- The helper remains synchronized on the Activity, preserving the lock order `Activity monitor -> workspace gate`.
- `isCurrentChat()` remains monitor-free. Do not add `@Synchronized` or another Activity-monitor acquisition to callbacks invoked under the workspace gate; that would introduce lock inversion.

### Regression protection
- Workspace operation lock harness: added deterministic recovery-commit vs chat-switch invalidation serialization case; 9/9 PASS.
- Workspace source guard: checks the chat-switch helper is synchronized and takes the global workspace gate; verifies recovery's chat predicate remains monitor-free.
- Recovery concurrency harness: source boundary now locates the helper by method signature instead of brittle comment text; 3/3 PASS.
- Full host regression: 174 executed cases PASS; compile shim `-Werror` PASS; static guards PASS.

### Limits
- Android/Gradle/Robolectric/SharedPreferences/device/process-death/APK remain NOT RUN because wrapper JAR, system Gradle and Android SDK are absent and `services.gradle.org` DNS is unavailable.
- App version remains `0.6.54 / code 80`; Companion Transport remains BLOCKED.
