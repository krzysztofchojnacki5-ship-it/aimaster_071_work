#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HARNESS="$ROOT/tools/patch_receipt_coordinator_harness"
BUILD="$HARNESS/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"
kotlinc -Werror -jvm-target 17 \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/PatchReceiptRecoveryPolicy.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/PatchReceiptRecoveryCoordinator.kt" \
  "$HARNESS/Runner.kt" -include-runtime -d "$BUILD/coordinator-tests.jar"
java -jar "$BUILD/coordinator-tests.jar"
