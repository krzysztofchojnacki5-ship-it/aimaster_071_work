#!/usr/bin/env bash
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0
printf 'AI MASTER PRO Android test environment preflight\n'
printf 'Project: %s\n' "$ROOT"
printf '\n'
if command -v java >/dev/null 2>&1; then
  printf '[PASS] Java: %s\n' "$(java -version 2>&1 | head -n 1)"
else
  printf '[BLOCKED] Java runtime not found\n'; FAIL=1
fi
if [[ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]]; then
  printf '[PASS] Gradle wrapper JAR present\n'
else
  printf '[BLOCKED] gradle/wrapper/gradle-wrapper.jar missing\n'; FAIL=1
fi
if command -v gradle >/dev/null 2>&1; then
  printf '[PASS] System Gradle: %s\n' "$(gradle --version 2>/dev/null | grep -m1 '^Gradle ' || true)"
else
  printf '[BLOCKED] System Gradle executable not found\n'; FAIL=1
fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$SDK" && -d "$SDK/platforms" ]]; then
  printf '[PASS] Android SDK platforms directory: %s\n' "$SDK/platforms"
  if [[ -d "$SDK/platforms/android-36" ]]; then
    printf '[PASS] Android platform android-36 present\n'
  else
    printf '[BLOCKED] Android platform android-36 missing\n'; FAIL=1
  fi
else
  printf '[BLOCKED] ANDROID_HOME/ANDROID_SDK_ROOT or SDK platforms directory missing\n'; FAIL=1
fi
if getent hosts services.gradle.org >/dev/null 2>&1; then
  printf '[PASS] services.gradle.org resolves via DNS\n'
else
  printf '[BLOCKED] services.gradle.org does not resolve via DNS; Gradle distribution cannot be fetched from this host\n'; FAIL=1
fi
printf '\n'
if [[ "$FAIL" -eq 0 ]]; then
  printf 'PREFLIGHT: READY (this does not itself run Gradle or Android tests)\n'
else
  printf 'PREFLIGHT: BLOCKED (no Gradle/Robolectric/Android build should be claimed)\n'
fi
exit "$FAIL"
