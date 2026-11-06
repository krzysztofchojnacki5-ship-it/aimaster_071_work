#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlinc -Werror \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/BundleContentScanner.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ExecutorProtocol.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ChangeSafetyGuard.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectPatchEngine.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceStore.kt" \
  "$ROOT/tools/workspace_transaction_recovery_harness/Runner.kt" \
  -include-runtime -d "$OUT/workspace-recovery.jar"
java -jar "$OUT/workspace-recovery.jar"
