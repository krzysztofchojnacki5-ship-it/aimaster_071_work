# AiFORTeca 0.2 — checkpoint 2026-11-12

## Repo state
- Repository: `krzysztofchojnacki5-ship-it/aimaster_071_work`
- Branch: `work/aiforteca-0.2-master-agent-foundation`
- Latest code commit: `6f44a88b47b21561621728d7b0c02ebf977c664a` (`test: cover denied replay and ambiguous recovery`)
- Previous checkpoint-only commit: `3714db4c434160f1aa834313e3760d3185add998`
- PR #3: open, draft; title: `AiFORTeca 0.2 — MASTER-led agent control center foundation`
- This checkpoint supersedes the earlier stale IPv6-only checkpoint text.

## Memory governance completed
1. `MemoryGovernance` defines USER / MASTER / AGENT / SYSTEM_RECOVERY scopes.
2. `MemoryMutationGate` requires governance approval and a durable INTENT record before a store mutation; it only reports success when the terminal audit record is persisted.
3. Governed stores require an internal mutation permit; agents and MASTER cannot directly mutate approved durable memory.
4. `MemoryAuditLog` is append-only and SHA-256 hash-chained. This detects accidental/tampered history but is not cryptographic protection against an attacker able to rewrite the whole journal and recompute hashes.
5. An unresolved INTENT or failed mutation blocks replay. Authorization-denial records do not mask a previously unresolved operation.
6. Proposal approval/rejection is routed through the gate; recovery is restricted to exact `proposal:<id>` references.

## Latest recovery hardening
Code commits:
- `128dada71bfd5cff34caef071371fc1cedcea013` — `fix: reconcile interrupted memory approvals safely`
- `2d7153eb46ab877a098a6119c711d631969fe7d2` — `test: fix interrupted proposal recovery fixture`
- `a714b578c461eafb7be012f6160d7eed32894ec9` — `test: model interrupted memory persistence directly`

Changes in the recovery hardening:
- Audit journal can report the latest unresolved operation for a proposal.
- Recovery closes the original USER operation only after the proposal ledger and all relevant memory stores have been checked against the desired state.
- Recovery terminal records use `RECOVERED_STATE`, preserving the original actor/mutation/object identity.
- Startup reconciliation checks the current durable state first and skips proposals that are already consistent; this avoids appending redundant recovery audit pairs on every app launch.
- If a previous SYSTEM_RECOVERY operation is unresolved and the stores still disagree with the proposal ledger, automatic replay is refused and the proposal is logged for manual inspection.
- A regression test simulates process interruption after proposal status persistence but before memory persistence, verifies recovery, verifies closure of the original USER intent, and verifies a second startup does not add redundant audit events.
- Additional tests cover interrupted SYSTEM_RECOVERY with mismatched stores (no automatic replay), and verify an authorization-denial record cannot mask an earlier unresolved USER intent.

Important limitation:
- Proposal ledger, shared memory, and agent memory are separate SharedPreferences files. They are not one cross-file atomic transaction. Recovery verifies and reconciles the durable source of truth; it does not claim cross-file atomicity.

## CI evidence
- Commit `798034d6dd4b1cb92ba018cfd3b5f6bb08a04357`: Android Build SUCCESS, including unit tests and `assembleRelease`; Gradle Wrapper Validation SUCCESS.
- Commit `6f44a88b47b21561621728d7b0c02ebf977c664a`: Android Build run `43120869622` SUCCESS, including `Run unit tests`, `Assemble release APK`, and artifact uploads; Gradle Wrapper Validation run `43120869605` SUCCESS.
- The Android Build produced an unsigned CI APK because release-signing setup is push-only; the PR run skipped signing and uploaded the unsigned release artifact.
- Earlier Android Build runs for `3714db4...` and `a714b57...` were cancelled after newer commits superseded them. Do not transfer their status to another SHA.
- No APK was sent to the user. Do not send APK or ZIP unless explicitly requested.

## Next actions
1. Verify CI for the newest HEAD after this checkpoint update: unit tests, then `assembleRelease`.
2. If a test fails, inspect the exact report/log and fix only the demonstrated cause.
3. Add a focused test that unreadable/corrupt audit history blocks a mutation and preserves the original journal bytes; rerun CI.
4. Continue auditing durable memory mutation call sites and recovery boundaries, including explicit user feedback and operational telemetry stores.
5. Do not mark device testing as passed; user performs Android Studio/device validation when requesting an APK.

## Package / artifact ledger
- Source baseline commits: `798034d...`, `128dada...`, `2d7153e...`, `a714b57...`, `6f44a88...`.
- Audited UI mutation entry points in `MainActivity`: manual shared-memory add and agent-memory removal use `MemoryMutationGate`; proposal approval/rejection uses `QualityLearningStore.setProposalStatus` and the gate; feedback ratings and proposal drafting are separate data records, not approved-memory mutations; clearing `LearningJournalStore` is a separately confirmed telemetry-only operation.
- APK / ZIP delivery: none.
- Current app version remains 0.2; do not bump to 0.3 until the governance/recovery stage and CI are verified.
