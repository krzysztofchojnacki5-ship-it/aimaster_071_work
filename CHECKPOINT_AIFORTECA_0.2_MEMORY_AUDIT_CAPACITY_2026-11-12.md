# AiFORTeca 0.2 — memory audit journal capacity checkpoint — 2026-11-12

## Verified starting point
- Repository: `krzysztofchojnacki5-ship-it/aimaster_071_work`
- Branch: `work/aiforteca-0.2-master-agent-foundation`
- Parent SHA: `c0e8e08f6dde5fb738a15205f659cd5d57757a5e`
- PR #3 remains open and Draft.
- Parent SHA CI: Android Build SUCCESS (run 43174362641); Gradle Wrapper Validation SUCCESS (run 43174363018).
- App version remains 0.2 / versionCode 3. No APK or ZIP delivered.

## Finding and correction
- `MemoryAuditLog.MAX_EVENTS` is 5,000 and the journal is append-only.
- A governed operation may need INTENT, then FAILED (if the callback partially fails), then RECOVERED_STATE after repair. Reserving only one slot after INTENT is insufficient.
- `MemoryAuditLog.append` now computes pending operation identities from the verified hash chain while holding its synchronized append monitor.
- Capacity policy after the proposed append:
  - latest INTENT reserves two future records (FAILED plus recovery terminal);
  - latest FAILED reserves one future recovery terminal;
  - latest COMMITTED reserves none;
  - DENIED_* records do not replace or mask a pending operation.
- A second INTENT for an unresolved identity is rejected. No historical record is truncated, rewritten, or overwritten.
- Capacity formula is checked before the SharedPreferences write; a rejected append leaves the journal unchanged. Since every append shares the MemoryAuditLog monitor, concurrent append callers cannot consume reserved capacity.

## Regression coverage added in this change
- Seed valid hash-chained journals near the 5,000-record boundary.
- Verify 4,997 existing records still permit INTENT, FAILED, and RECOVERED_STATE to reach exactly 5,000 while preserving prior history.
- Verify INTENT is rejected at 4,998, 4,999, and 5,000 records.
- Verify MemoryMutationGate does not invoke the mutation callback when three slots cannot be guaranteed.
- CI for the new commit is pending; do not mark tests/build as passed until the exact resulting SHA has green Android Build and Gradle Wrapper Validation workflows.

## Remaining caveats / next audit
- Existing installations may already have a full journal with an unresolved operation from the old policy. This change deliberately fails closed; it does not discard history or fabricate a terminal event. Such a journal requires a separately designed verified export/archive or manual recovery process.
- Continue fault injection for approval compensation failures and ambiguous SharedPreferences commits, then audit proposal-ledger write/recovery boundaries.
- Physical-device testing remains separate from CI. APK delivery only on explicit request.
