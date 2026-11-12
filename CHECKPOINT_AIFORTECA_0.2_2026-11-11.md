# AiFORTeca 0.2 — checkpoint 2026-11-12

## Repo state
- Repository: `krzysztofchojnacki5-ship-it/aimaster_071_work`
- Branch: `work/aiforteca-0.2-master-agent-foundation`
- Verified base for this checkpoint update: `039c22b5da0bdcc8d2ee5dce80c0384548f2c0c8` (`test: disambiguate replay approval lambdas`).
- PR #3: open, draft; title: `AiFORTeca 0.2 — MASTER-led agent control center foundation`.
- App version remains 0.2 / versionCode 3. Do not bump until the current memory-governance stage and CI are verified.
- GitHub branch is the source of truth. No APK or ZIP has been delivered; do not send either unless explicitly requested.

## Memory governance already implemented
1. `MemoryGovernance` defines USER / MASTER / AGENT / SYSTEM_RECOVERY scopes.
2. `MemoryMutationGate` requires authorization and a durable INTENT record before mutation; it reports success only if both mutation and terminal audit append succeed.
3. Governed stores require an internal mutation permit; MASTER and AGENT cannot directly mutate approved durable memory.
4. `MemoryAuditLog` is append-only and SHA-256 hash-chained. It detects journal corruption but is not cryptographic protection against an attacker able to rewrite the entire journal and recompute hashes.
5. An unresolved INTENT blocks replay. Authorization-denial records do not mask an earlier unresolved operation.
6. A committed USER approvalRef is treated as an idempotency key; replay of the same actor/mutation/object/approvalRef is rejected.
7. Recovery is restricted to exact `proposal:<id>` references and verifies durable state before resolving an original operation.
8. Proposal ledger, shared memory, and agent memory live in separate SharedPreferences files; they are not a cross-file atomic transaction.

## Recent CI evidence
- Commit `64d70a5b9c32ba95ece01f38b9bcee5e5e04eb18`: Android Build SUCCESS, including unit tests and `assembleRelease`; Gradle Wrapper Validation SUCCESS.
- Commit `f13c7eeae630bd0d4553aae3220aea2386d8e5ee`: Android Build FAILED in test compilation because two overloaded `MemoryMutationGate.execute` calls in `MemoryMutationGateTest` used ambiguous untyped lambdas; Gradle Wrapper Validation SUCCESS.
- Commit `bb4d40ba1eb0d8b9a753f4b75761de81e5c47503`: Gradle Wrapper Validation SUCCESS; Android Build FAILED at `compileDebugUnitTestKotlin`. The previous change typed lambdas in the distinct-approval-reference test but missed the two calls in `replayOfCommittedApprovalReferenceIsRejectedWithoutMutation`. The compiler still reports overload ambiguity at test lines 69 and 76. Main Kotlin source compilation completed; unit-test compilation did not. `assembleRelease` was skipped because tests failed.
- Android Build run: `43165260995`; job: `131005531763`.
- Gradle Wrapper Validation run: `43165261018`; job: `131005544412`.
- Commit `039c22b5da0bdcc8d2ee5dce80c0384548f2c0c8`: Android Build run `43165768564` SUCCESS; unit tests, report upload, `Assemble release APK`, source packaging/upload, and unsigned APK upload all completed successfully. Gradle Wrapper Validation run `43165768519` SUCCESS. No device testing is claimed.

## Completed since the previous checkpoint
- Fixed the two ambiguous replay-test lambda overloads by explicitly typing `MemoryMutationPermit`; CI on `039c22b5` is green, including unit tests and release APK assembly.

## Current audit finding: failed partial mutations
- `MemoryMutationGate.execute` records `FAILED / MUTATION_FAILED` when the callback returns false or throws.
- This is **not treated as a resolved operation**: `hasUnresolvedIntent` returns true for every latest phase other than `COMMITTED`, and `unresolvedOperations` likewise retains `FAILED` records. The next attempt is blocked until recovery verifies state.
- `QualityLearningStore.setProposalStatusRaw` is a multi-store workflow: it persists proposal status, then writes/removes shared memory and agent memory; on failure it attempts compensating rollback. Those files are not atomic together. Startup reconciliation compares proposal ledger and both memory stores, and skips recovery if any source is unreadable.
- Added regression test `partialMutationFailureRemainsUnresolvedAndCannotBeReplayed` to prove a false result after a simulated durable side effect remains unresolved and cannot be blindly replayed. This test awaits CI on the next commit.

## Next audit work
1. Verify the unexpected-agent provenance regression test and release assembly in CI for the current commit.
2. Review compensation/recovery for every approval/rejection path, especially whether rollback failures leave enough evidence for startup reconciliation or manual inspection.
3. Continue source-level audit of durable memory mutation and journal write/retry paths.
4. Update this checkpoint after meaningful verified work; tie all CI status to exact commit SHAs.
5. Device/Ollama connectivity remains a separate manual test for the user when an APK is explicitly requested.

## Prior recovery work retained
- Startup reconciliation checks proposal ledger integrity and inspects shared/agent memory before changing anything.
- Already-consistent proposals are skipped to avoid redundant audit records.
- Recovery closes the original USER operation only after desired durable state is verified.
- Recovery terminal records use `RECOVERED_STATE`, preserving original actor/mutation/object identity.
- An unresolved SYSTEM_RECOVERY operation with mismatched stores blocks automatic replay and requires manual inspection.
- Tests cover interrupted approval persistence, recovery, idempotent startup reconciliation, unresolved recovery refusal, denial not masking unresolved USER intent, corrupt audit journal fail-closed behavior, and duplicate approvalRef rejection.

## Package / artifact ledger
- Current version: 0.2 / versionCode 3.
- CI artifacts are not user-delivered packages.
- APK delivery: none. ZIP delivery: none.


## Verified follow-up — CI and agent-scope normalization
- Commit `34d16759f80495ce154e4ee48c7ab8fce29107fa`: Android Build SUCCESS (unit tests and `assembleRelease`); Gradle Wrapper Validation SUCCESS. This is CI evidence for this exact SHA, not device testing.
- The approved-proposal recovery comparison now trims agent IDs before case normalization. Previously, an affected agent recorded as `" coder "` was persisted as `coder` by the agent store but compared without trimming during reconciliation, which could cause unnecessary recovery on startup.
- Added `approvedProposalScopeNormalizationDoesNotTriggerRepeatedRecovery`: verifies the normalized scope and that repeated startup reconciliation adds no audit events.
- This follow-up change is prepared against the verified HEAD above; CI must be run for its resulting commit before it is considered verified.
- Next audit: compensation failures in approval/rejection multi-store writes, then durable journal/recovery fault injection. SharedPreferences files remain non-atomic across stores.


## Verified follow-up — current HEAD and compensation fault injection
- Commit `cd5f5cdc0f40954855d15e24e6bf7970d7f62216`: Android Build SUCCESS (unit tests, release APK assembly, source packaging, unsigned APK artifact upload); Gradle Wrapper Validation SUCCESS. This is CI evidence for this exact SHA, not physical-device testing.
- Confirmed branch HEAD and PR #3 head both point to `cd5f5cdc0f40954855d15e24e6bf7970d7f62216`; PR remains open/draft.
- Source audit of `setProposalStatusRaw` confirmed that a failed rejection cleanup rolls the proposal ledger back to APPROVED but does not immediately restore a shared-memory entry already removed. Startup reconciliation can restore it only when all stores become readable; it deliberately fails closed while the agent ledger is unreadable.
- Added `rejectionPartialCleanupFailureIsRecoveredFromApprovedLedgerAfterStoreRepair`: approve a proposal, corrupt the agent ledger, attempt rejection, verify shared-memory removal plus proposal-status rollback and unresolved USER intent, restore the agent ledger, then verify reconciliation restores both approved memory stores and closes the original intent. This is a targeted regression test; it awaits CI on the new commit.
- No production behavior was changed in this test-only step. The observed partial state is recoverable after the unreadable store is repaired; cross-file atomicity is still not claimed.
- Next: verify this test on CI, then continue fault injection for approval rollback failures and durable audit/proposal journal write failures.