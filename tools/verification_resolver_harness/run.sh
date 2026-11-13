#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HARNESS="$ROOT/tools/verification_resolver_harness"
BUILD="$HARNESS/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
javac -d "$BUILD/classes" "$HARNESS/Test.java" "$HARNESS/Assert.java"
kotlinc -Werror -jvm-target 17 -classpath "$BUILD/classes" \
  "$HARNESS/Runner.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/Models.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/agents/AgentPrompts.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/VerificationEvidence.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/VerificationEvidenceStore.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ToolExecutionRegistry.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/EvidenceTrackedToolAdapter.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/VerificationEvidenceResolver.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/MasterEvidenceContextBuilder.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/HostActiveEvidenceScopeResolver.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceOperationLock.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceIdentityGuard.kt" \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/MasterResponseCheck.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/agents/AgentPromptsTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/VerificationEvidenceResolverTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/MasterEvidenceContextBuilderTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/HostActiveEvidenceScopeResolverTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/ProjectWorkspaceOperationLockTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/ToolExecutionRegistryTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/VerificationEvidenceStoreTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/VerificationEvidenceTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/EvidenceTrackedToolAdapterTest.kt" \
  "$ROOT/app/src/test/java/pl/aimasterpro/app/core/MasterResponseCheckTest.kt" \
  -include-runtime -d "$BUILD/resolver-tests.jar"
jar uf "$BUILD/resolver-tests.jar" -C "$BUILD/classes" .
java -jar "$BUILD/resolver-tests.jar"
