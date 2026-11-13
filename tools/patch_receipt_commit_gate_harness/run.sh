#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlinc -Werror -jvm-target 17 \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceOperationLock.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/PatchReceiptRecoveryCommitGate.kt" \
  "$ROOT/tools/patch_receipt_commit_gate_harness/Runner.kt" \
  -include-runtime -d "$OUT/commit-gate.jar"
java -jar "$OUT/commit-gate.jar"
