#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# Static integration guard: MainActivity must use a volatile chat id and a monitor-free check.
grep -Fq '@Volatile private var currentChatId = ""' "$ROOT/app/src/main/java/pl/aimasterpro/app/MainActivity.kt"
if grep -Fq '@Synchronized private fun isCurrentChat' "$ROOT/app/src/main/java/pl/aimasterpro/app/MainActivity.kt"; then
  echo 'FAIL MainActivity.isCurrentChat must not acquire the activity monitor' >&2
  exit 1
fi
# Evidence selection must atomically probe pending transaction state and read the active
# pointer under the same non-blocking workspace operation lock.
python3 - "$ROOT/app/src/main/java/pl/aimasterpro/app/MainActivity.kt" <<'PYGUARD'
import pathlib, sys
source = pathlib.Path(sys.argv[1]).read_text()
start = source.index("private fun buildMasterEvidenceContext(")
end = source.index("@Synchronized private fun invalidateActiveEvidenceScopeForChatSwitch", start)
fn = source[start:end]
required = [
    "ProjectWorkspaceOperationLock.tryWithLock {",
    "hasPendingWorkspaceTransaction()",
    "HostActiveEvidenceStartupGate.resolve(transactionState)",
    "HostActiveChangeSetStore.readActive(",
    "Workspace jest zajęty lub nie można bezpiecznie odczytać jego stanu",
]
positions = [fn.find(token) for token in required]
if any(pos < 0 for pos in positions) or positions != sorted(positions):
    raise SystemExit("FAIL evidence probe/read must be ordered inside one tryWithLock section")
lock_start = fn.find(required[0])
read_pos = fn.find(required[3])
# Balance braces from the tryWithLock opening; its closure must contain readActive().
brace = fn.find("{", lock_start)
depth = 0
lock_end = -1
for i in range(brace, len(fn)):
    if fn[i] == "{": depth += 1
    elif fn[i] == "}":
        depth -= 1
        if depth == 0:
            lock_end = i
            break
if lock_end < 0 or not (brace < read_pos < lock_end):
    raise SystemExit("FAIL active pointer read is outside workspace operation lock")
print("PASS pending transaction probe and active pointer read share one atomic workspace lock")

# Patch-apply failure reconciliation must serialize its pending probe, optional REJECTED
# transition, and active-pointer invalidation as one unit.
fail_start = source.index("if (outcome.isFailure && changeSetId.isNotBlank())")
fail_end = source.index("} else if (!statusPersisted)", fail_start)
fail_path = source[fail_start:fail_end]
lock_token = "ProjectWorkspaceOperationLock.withLock {"
lock_start = fail_path.find(lock_token)
if lock_start < 0:
    raise SystemExit("FAIL patch failure reconciliation is not workspace-locked")
brace = fail_path.find("{", lock_start)
depth = 0
lock_end = -1
for i in range(brace, len(fail_path)):
    if fail_path[i] == "{": depth += 1
    elif fail_path[i] == "}":
        depth -= 1
        if depth == 0:
            lock_end = i
            break
critical = fail_path[brace:lock_end + 1] if lock_end >= 0 else ""
for token in ["hasPendingWorkspaceTransaction()", "ChangeSetStore.transition(this, changeSetId, \"REJECTED\")", "HostActiveChangeSetStore.invalidate(this, changeSetId)"]:
    if token not in critical:
        raise SystemExit(f"FAIL failure reconciliation operation outside lock: {token}")

# The post-recovery diagnostic must observe workspace identity and active pointer under
# the same worker-thread lock; this is diagnostic only, but mixed generations are misleading.
activated_start = source.index("val activated: Boolean? = if (!eligibleForActivation")
activated_end = source.index("logAgentForChat(receipt.chatId, when", activated_start)
activated = source[activated_start:activated_end]
log_end = source.index('}.apply { name = "PatchApplyRecovery"', activated_end)
activation_log = source[activated_end:log_end]
if "ProjectWorkspaceOperationLock.withLock {" not in activated or "workspace.currentIdentity()" not in activated or "HostActiveChangeSetStore.readActive(" not in activated or "activated == null" not in activation_log:
    raise SystemExit("FAIL post-recovery identity/pointer read is not atomic or observation failure is conflated with scope mismatch")
print("PASS failure reconciliation and post-recovery diagnostic share workspace lock")

# Normal patch apply may acknowledge a receipt only while holding the shared lock and
# after rechecking the workspace generation that the patch actually produced.
ack_start = source.index("workspaceStore.acknowledgePatchReceipt(changeSetId, applied!!.transactionId)")
ack_lock_start = source.rfind("ProjectWorkspaceOperationLock.withLock {", 0, ack_start)
if ack_lock_start < 0:
    raise SystemExit("FAIL normal patch receipt acknowledgement is outside workspace lock")
ack_brace = source.find("{", ack_lock_start)
depth = 0
ack_lock_end = -1
for i in range(ack_brace, len(source)):
    if source[i] == "{": depth += 1
    elif source[i] == "}":
        depth -= 1
        if depth == 0:
            ack_lock_end = i
            break
ack_critical = source[ack_brace:ack_lock_end + 1] if ack_lock_end >= 0 else ""
if "ProjectWorkspaceIdentityGuard.requireUnchanged(" not in ack_critical:
    raise SystemExit("FAIL receipt acknowledgement lacks in-lock workspace identity recheck")
print("PASS normal patch receipt acknowledgement is identity-fenced under workspace lock")
PYGUARD
kotlinc -Werror -jvm-target 17 \
  "$ROOT/app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceOperationLock.kt" \
  "$ROOT/tools/patch_recovery_concurrency_harness/Runner.kt" \
  -include-runtime -d "$OUT/recovery-concurrency.jar"
java -jar "$OUT/recovery-concurrency.jar"
echo 'PATCH_RECOVERY_MAIN_ACTIVITY_LOCK_ORDER_GUARD: PASS'
