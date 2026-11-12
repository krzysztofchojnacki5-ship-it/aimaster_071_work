# AiFORTeca 0.2 — shared memory capacity and mutation checkpoint — 2026-11-12

## Verified starting point
- Repository: `krzysztofchojnacki5-ship-it/aimaster_071_work`
- Branch: `work/aiforteca-0.2-master-agent-foundation`
- Parent commit: `efa6c4612fe04c8c912800e61c35644893eed18c`
- Previous CI for parent: Android Build push and PR SUCCESS; Gradle Wrapper Validation push and PR SUCCESS.
- App remains `0.2` / `versionCode 3`; no device tests and no 0.3 release.

## Package — three shared-memory corrections
1. Persisted reader enforces <=100 entries, rejects blank/oversized strings and duplicates; malformed journals fail closed.
2. Additions reject strings >2,000 chars and full capacity instead of silently truncating with `take(100)`.
3. Removing a built-in default rule before first preference write persists the removal; unknown removals return false.

## Regression tests
- Oversized, blank and duplicate persisted rows fail closed and remain unchanged after attempted mutation.
- Oversized additions and 101st entry are rejected without dropping any existing entry.
- Removing a default entry is persisted; unknown removal returns false.

## Verification
- Source, regression tests and this checkpoint are in one commit.
- CI pending for this exact commit. No local build or device test claimed.

## Follow-up package — idempotent proposal cleanup — 2026-11-12
- Root cause in both failing QualityLearningStore recovery tests: AiMemoryStore.removeMemory returned false when the target entry was already absent. Proposal rollback/reconciliation therefore failed despite the desired durable state already being present.
- Fix: removal is idempotent only for APPROVE_PROPOSAL, REJECT_PROPOSAL and the two SYSTEM_RECOVERY reconciliation mutations. Direct REMOVE_SHARED_RULE remains strict and returns false for an absent entry.
- Regression coverage: recovery succeeds when the shared-memory entry is already absent; manual deletion of an absent rule still fails.
- This patch is source-reviewed but not yet CI-verified. No APK or test pass is claimed.
