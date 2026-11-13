# CHANGELOG 0.6.121 — Ambiguous SharedPreferences commit recovery

## Problem
`SharedPreferences.Editor.commit()` can return `false` after the process-local preferences map has already been updated. The previous adapter propagated `false`, but did not compensate the in-memory value. A subsequent read in the same process could therefore observe a pointer/value from an operation that was reported as failed.

## Change
`AndroidStringPreferenceStore` now captures the previous value before write/remove. If `commit()` returns false or throws, it attempts a compensating operation restoring the previous value (or removing a newly-created key). The original operation always returns `false`; compensation never upgrades it to success.

This is best-effort compensation, not proof of durable disk state if the compensating commit also fails. Callers must continue treating the operation as failed and retry/reconcile rather than claim persistence.

## Tests
- Extended host harness with a SharedPreferences-compatible in-memory implementation that applies the editor mutation and then returns `false`, reproducing the ambiguous process-memory state.
- Failed write restores the previous value: PASS.
- Failed remove restores the previous pointer: PASS.
- ChangeSet storage fault matrix: 15/15 PASS (`kotlinc -Werror`).
- Commit gate 9/9; coordinator 19/19; policy 11/11; protocol 8/8; concurrency 3/3; workspace lock 5/5; workspace transaction recovery 12/12; verification resolver 73/73.
- Total executed host cases in this iteration: 155 PASS. MainActivity lock-order guard PASS.
- Filesystem integration harness timed out in this iteration; do not carry forward its prior 0.6.120 result as a new run.

## Not run
Gradle, Robolectric, real Android SharedPreferences, Android build, APK and device tests NOT RUN. The fake SharedPreferences implementation verifies adapter compensation logic but does not prove Android disk durability or process-death behavior.

## Versioning
Application version remains `0.6.54` / `versionCode 80`. Companion transport remains BLOCKED.
