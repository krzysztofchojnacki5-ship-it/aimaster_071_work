#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HARNESS="$ROOT/tools/staged_import_contract_harness"
BUILD="$HARNESS/build"
rm -rf "$BUILD" && mkdir -p "$BUILD"
kotlinc -Werror -jvm-target 17 "$HARNESS/Runner.kt" -include-runtime -d "$BUILD/contract-tests.jar"
java -jar "$BUILD/contract-tests.jar"
python3 "$HARNESS/SourceGuard.py"
