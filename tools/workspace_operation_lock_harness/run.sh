#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HARNESS="$ROOT/tools/workspace_operation_lock_harness"
BUILD="$HARNESS/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"
kotlinc -Werror -jvm-target 17 \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceOperationLock.kt" \
  "$HARNESS/Runner.kt" -include-runtime -d "$BUILD/lock-tests.jar"
java -jar "$BUILD/lock-tests.jar"
python3 "$HARNESS/SourceGuard.py"
python3 "$HARNESS/WorkspaceCallSiteGuard.py"
