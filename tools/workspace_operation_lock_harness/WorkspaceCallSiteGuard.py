#!/usr/bin/env python3
"""Fail-closed source audit for every production ProjectWorkspaceStore I/O call site."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main/java/pl/aimasterpro/app/MainActivity.kt"
RUNNER = ROOT / "app/src/main/java/pl/aimasterpro/app/core/ProjectTestRunner.kt"
COMMIT_GATE = ROOT / "app/src/main/java/pl/aimasterpro/app/core/PatchReceiptRecoveryCommitGate.kt"


def delimiter_pairs(source: str):
    """Match Kotlin structural delimiters while ignoring comments and quoted literals."""
    stack = []
    pairs = {}
    i = 0
    state = "code"
    while i < len(source):
        c = source[i]
        n = source[i + 1] if i + 1 < len(source) else ""
        if state == "code":
            if c == "/" and n == "/":
                state = "line_comment"
                i += 2
                continue
            if c == "/" and n == "*":
                state = "block_comment"
                i += 2
                continue
            if c == '"':
                if source.startswith('"""', i):
                    state = "triple_string"
                    i += 3
                    continue
                state = "string"
                i += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                continue
            if c in "({[":
                stack.append((c, i))
            elif c in "}])":
                expected = {")": "(", "}": "{", "]": "["}[c]
                if not stack or stack[-1][0] != expected:
                    raise AssertionError(f"Unbalanced Kotlin delimiter {c!r} at offset {i}")
                _, opening = stack.pop()
                pairs[opening] = i
        elif state == "line_comment":
            if c == "\n":
                state = "code"
        elif state == "block_comment":
            if c == "*" and n == "/":
                state = "code"
                i += 2
                continue
        elif state == "string":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = "code"
        elif state == "triple_string":
            if source.startswith('"""', i):
                state = "code"
                i += 3
                continue
        elif state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = "code"
        i += 1
    if stack:
        raise AssertionError(f"Unclosed Kotlin delimiter(s): {stack[-5:]}")
    return pairs


main = MAIN.read_text()
runner = RUNNER.read_text()
gate = COMMIT_GATE.read_text()
pairs = delimiter_pairs(main)

# Any direct store I/O call must be under the process-wide workspace lock, or be
# a callback passed to PatchReceiptRecoveryCommitGate (which owns that lock).
methods = (
    "currentInfo currentIdentity hasPendingWorkspaceTransaction readPendingPatchReceipt "
    "acknowledgePatchReceipt markPatchReceiptRollbackRequested markPatchReceiptRolledBack "
    "recoverRequestedPatchRollback importZip snapshots snapshotIdentity verifySnapshot "
    "restoreSnapshot exportSnapshotBundle listFiles readTextFile verifyCurrentWorkspace applyPreview"
).split()
method_pattern = re.compile(r"\.\s*(" + "|".join(methods) + r")\s*\(")
lock_pattern = re.compile(r"ProjectWorkspaceOperationLock\.(?:withLock|tryWithLock)\s*\{")
lock_ranges = []
for match in lock_pattern.finditer(main):
    opening = main.find("{", match.start(), match.end())
    if opening not in pairs:
        raise AssertionError("Workspace lock lambda has no matching closing brace")
    lock_ranges.append((opening, pairs[opening]))

commit_match = re.search(r"PatchReceiptRecoveryCommitGate\.commit\s*\(", main)
if not commit_match:
    raise AssertionError("Missing PatchReceiptRecoveryCommitGate.commit call")
commit_open = main.find("(", commit_match.start(), commit_match.end())
commit_close = pairs.get(commit_open)
if commit_close is None:
    raise AssertionError("Cannot determine PatchReceiptRecoveryCommitGate.commit argument scope")

calls = list(method_pattern.finditer(main))
if len(calls) != 39:
    raise AssertionError(f"Workspace API call inventory changed: expected 39, found {len(calls)}; review new call sites")

delegated = []
unlocked = []
for call in calls:
    if any(start < call.start() < end for start, end in lock_ranges):
        continue
    if commit_open < call.start() < commit_close:
        delegated.append(call.group(1))
    else:
        line = main.count("\n", 0, call.start()) + 1
        unlocked.append(f"line {line}: {call.group(1)}")
if unlocked:
    raise AssertionError("Workspace I/O outside a lock-owned boundary: " + ", ".join(unlocked))
if sorted(delegated) != ["acknowledgePatchReceipt", "currentIdentity"]:
    raise AssertionError(f"Unexpected lock-delegated callbacks: {delegated}")

# Chat-switch invalidation must share the workspace gate with recovery/patch activation.
# The method remains @Synchronized, establishing the documented Activity-monitor -> workspace-gate order.
switch_match = re.search(r"@Synchronized\s+private fun invalidateActiveEvidenceScopeForChatSwitch\(\)", main)
if not switch_match:
    raise AssertionError("Missing synchronized chat-switch evidence invalidation boundary")
method_open = main.find("{", switch_match.end())
method_close = pairs.get(method_open)
if method_close is None:
    raise AssertionError("Cannot determine chat-switch invalidation method scope")
switch_body = main[method_open:method_close + 1]
if "ProjectWorkspaceOperationLock.withLock {" not in switch_body or "HostActiveChangeSetStore.invalidate(applicationContext)" not in switch_body:
    raise AssertionError("Chat-switch active-scope invalidation must run inside the global workspace lock")

# Recovery callbacks called while holding the workspace gate must not acquire the Activity monitor.
if "chatStillMatches = { isCurrentChat(receipt.chatId) }" not in main:
    raise AssertionError("Recovery chat check must remain lock-free to preserve lock order")

# The two delegated callbacks are safe only because the gate itself owns the lock.
if "= ProjectWorkspaceOperationLock.withLock {" not in gate:
    raise AssertionError("PatchReceiptRecoveryCommitGate must acquire the global workspace lock")
if "currentWorkspaceIdentity()" not in gate or "acknowledge()" not in gate:
    raise AssertionError("Commit gate no longer invokes its guarded callbacks")

# Store construction and all production I/O are intentionally centralized in MainActivity
# and the self-locking ProjectTestRunner. New production entry points require an explicit audit.
production = ROOT / "app/src/main/java"
constructors = []
for source_file in production.rglob("*.kt"):
    if source_file.name == "ProjectWorkspaceStore.kt":
        continue
    contents = source_file.read_text()
    if "ProjectWorkspaceStore(" in contents:
        constructors.append(source_file.relative_to(ROOT).as_posix())
if constructors != ["app/src/main/java/pl/aimasterpro/app/MainActivity.kt"]:
    raise AssertionError(f"New ProjectWorkspaceStore construction site(s) require audit: {constructors}")

runner_public_start = runner.index("fun verifyAppliedChange(")
runner_public_end = runner.index("private fun verifyAppliedChangeLocked(", runner_public_start)
runner_public = runner[runner_public_start:runner_public_end]
if "= ProjectWorkspaceOperationLock.withLock {" not in runner_public:
    raise AssertionError("ProjectTestRunner public verification entry point lost its lock boundary")

print(f"PASS production workspace API inventory: {len(calls)} call sites, all lock-covered")
print("PASS delegated recovery callbacks: currentIdentity + acknowledgePatchReceipt under commit gate")
print("PASS store construction remains centralized; ProjectTestRunner public boundary self-locks")
