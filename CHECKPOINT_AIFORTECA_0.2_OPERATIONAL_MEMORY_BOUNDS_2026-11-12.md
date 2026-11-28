# AiFORTeca 0.2 — operational memory bounds checkpoint — 2026-11-12

## Verified starting point
- Repository: `krzysztofchojnacki5-ship-it/aimaster_071_work`
- Branch: `work/aiforteca-0.2-master-agent-foundation`
- Parent commit: `2569dfa54dd3234d22cf37e2af90d6c8da3723c7`
- App version remains `0.2` / `versionCode 3`.
- Android Build push run `43280107550`: **SUCCESS**. Unit tests, release APK assembly, source packaging and signed APK upload all completed successfully.
- Android Build pull-request run `43280121635`: **SUCCESS**. Unit tests, release APK assembly, source packaging and unsigned APK upload all completed successfully.
- Gradle Wrapper Validation push run `43280107551` and pull-request run `43280121728`: **SUCCESS**.
- Artifacts: signed APK `13971884086`, source project `13972183279`, unit test reports `13970939860` (push run).
- Physical-device tests remain NOT RUN. CI artifacts are not being handed off as version 0.3.

## Next package — three operational-memory durability corrections
Audit found three silent-data-loss paths in `AgentOperationalMemoryStore`:

1. **Persisted field bounds:** the read path previously truncated oversized text/category/confidence values in memory instead of rejecting the malformed ledger. A later valid mutation could then rewrite the file with truncated values. The new read validation rejects oversized IDs, agent IDs, text, category, confidence and provenance IDs; no truncation occurs at the read boundary.
2. **Input and store-capacity validation:** the write path previously used `take(MAX_ENTRIES)`, which could silently discard older records. New writes reject invalid/oversized inputs and a full store rather than truncating input or deleting records.
3. **Provenance preservation:** duplicate lesson merges previously used `take(30)`, silently dropping source task IDs beyond the cap. The new behavior rejects a merge that would exceed 30 provenance IDs and leaves the stored record unchanged.

## Regression coverage added
- Oversized persisted values across all bounded fields are rejected; raw bytes remain unchanged after attempted mutation.
- Oversized agent/text/category and malformed/oversized source IDs are rejected before memory persistence.
- A duplicate lesson with 30 existing provenance IDs rejects a 31st source and preserves the original serialized record.
- Existing legacy rows with absent confidence remain readable (empty confidence is allowed for backward compatibility).

## Verification status
- Code changes are committed as one three-fix package.
- CI for this exact commit: PENDING until the Android Build and Gradle Wrapper Validation workflows finish.
- No local build or physical-device test is claimed.
- Do not create or deliver APK 0.3 until the planned release scope and acceptance checks are complete.
