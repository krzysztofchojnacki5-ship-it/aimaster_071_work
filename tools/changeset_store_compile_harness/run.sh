#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HARNESS="$ROOT/tools/changeset_store_compile_harness"
BUILD="$HARNESS/build"
rm -rf "$BUILD"
mkdir -p "$BUILD"
kotlinc -Werror -jvm-target 17 \
  "$HARNESS/stubs/android/content/Context.kt" \
  "$HARNESS/stubs/org/json/Json.kt" \
  "$HARNESS/stubs/pl/aimasterpro/app/core/ChangeSafetyGuard.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/StringPreferenceStore.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ChangeSetStore.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/HostActiveChangeSetStore.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/HostActiveEvidenceStartupGate.kt" \
  -d "$BUILD/changeset-store-compile.jar"
printf '%s\n' 'PASS ChangeSetStore + HostActiveChangeSetStore compile with -Werror against compile-only Android/JSON API shims.'
