#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlinc -Werror -jvm-target 17 \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/BundleContentScanner.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ExecutorProtocol.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ChangeSafetyGuard.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectPatchEngine.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceStore.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/PatchReceiptRecoveryPolicy.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/PatchReceiptRecoveryCoordinator.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/PatchReceiptRecoveryCommitGate.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceOperationLock.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceIdentityGuard.kt" \
  "$ROOT/tools/patch_receipt_filesystem_integration_harness/Runner.kt" \
  -include-runtime -d "$OUT/filesystem-integration.jar"
java -jar "$OUT/filesystem-integration.jar"
