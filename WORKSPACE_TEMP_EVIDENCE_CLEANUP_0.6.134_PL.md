# 0.6.134 — fail-closed cleanup of interrupted workspace evidence

## Problem found

`cleanupInterruptedStaging()` previously removed `.staging-*`, `.patch-staging-*`, `.patch-recovery-*` and temporary swap/patch-receipt files before checking whether `current/` matched `current.manifest`. The later verification guarded only `.previous-*` pruning. A directory plus a manifest file was therefore enough to trigger deletion of transaction evidence, even when the manifest was corrupt or the current tree did not verify.

## Production change

The method now performs the full current-tree/manifest verification first. If verification fails or throws, it returns without deleting any staging, temporary journal/receipt, or previous artifacts. Only a verified current pair authorizes the cleanup phase. Symlink temporary entries are deleted as directory entries; cleanup does not traverse their targets.

This is deliberately fail-closed. It can leave stale temporary files when there is no independently verified current workspace; retaining evidence is safer than deleting material that may be needed for manual or future recovery.

## Tests

The real `ProjectWorkspaceStore.kt` host harness now covers:

- verified current: orphan swap temp and patch-receipt temp are removed;
- verified current: a symlink temp entry is removed without touching its outside target;
- corrupt current manifest: orphan temp evidence is preserved;
- previous 13 recovery cases remain passing.

The harness result is 15/15. A corresponding Android/JUnit regression test was added but not executed because Gradle/Android SDK are unavailable.

## Limits

- This is a production Kotlin source change validated on a host JVM, not Android filesystem/power-loss proof.
- No changes to AISW v1/v2 journal format, writer, commit point, staged import protocol, or lock scope.
- `importZip()` remains under the global workspace lock.
- Companion Transport remains BLOCKED.
