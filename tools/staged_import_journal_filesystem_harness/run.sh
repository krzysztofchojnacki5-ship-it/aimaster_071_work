#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HARNESS="$ROOT/tools/staged_import_journal_filesystem_harness"
BUILD="$HARNESS/build"
rm -rf "$BUILD" && mkdir -p "$BUILD"
KOTLIN_LIB="${KOTLIN_LIB:-/root/.sdkman/candidates/kotlin/1.9.0/lib}"
java -Xmx1g --add-opens java.base/java.util=ALL-UNNAMED -cp "$KOTLIN_LIB/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -Werror -jvm-target 17 "$HARNESS/Runner.kt" -include-runtime -d "$BUILD/journal-tests.jar"
java -jar "$BUILD/journal-tests.jar"
