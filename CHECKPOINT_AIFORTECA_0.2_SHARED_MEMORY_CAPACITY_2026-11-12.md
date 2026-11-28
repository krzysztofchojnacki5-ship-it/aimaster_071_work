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

## Follow-up package — chat archive and task-ID integrity — 2026-11-12
- Previous commit `094bbb84e56159043987ba36bae10f334fac0ce6` CI completed successfully: unit tests, Gradle Wrapper validation, release APK assembly and source packaging all passed; signed release APK and reports were uploaded. This is still version 0.2.
- Code commit: `f544593b3d744195cf08c07a47bd15d323ba97df`.
- Correction 1: ChatResponseArchive now rejects persisted archives with more than its declared 25-entry capacity instead of accepting oversized journals.
- Correction 2: task ID, agent ID, provider ID and model metadata have explicit length bounds on both writes and reads; malformed persisted metadata fails closed and is preserved.
- Correction 3: ChatHistoryStore rejects duplicate nonblank assistant task IDs on both read and write, while preserving repeated empty placeholders used for legacy message alignment.
- Regression tests cover over-capacity archive preservation, oversized metadata on write/read, and duplicate assistant task-ID rejection without overwriting the source history.
- CI for this code commit: queued at the time of this checkpoint. No test/build result is claimed yet. No device test and no 0.3 release claimed.
 
## Follow-up package — task result archive bounds — 2026-11-12
- Code changes: TaskResultArchive capacity and metadata validation.
- Correction 1: persisted task-result archives larger than the declared 80-entry capacity are rejected as corrupt; original bytes are preserved and new writes are blocked.
- Correction 2: task, agent, provider and model identifiers now have explicit length limits on both write and read paths.
- Correction 3: chat ID length is validated on both write and read paths, while legacy rows with no chat ID remain supported as an empty ID.
- Regression tests cover the 81-row archive, oversized metadata on writes, and oversized metadata/chat-ID on reads; malformed data must not be overwritten.
- Validation status at commit time: pending CI. No device test and no 0.3 release claimed.


## Follow-up package — bounded conversation restoration, legacy import and pins — 2026-11-12
- Correction 1: ChatConversationSnapshot rejects oversized restored collections and fields (5,000 messages/task IDs, 150 activity rows, bounded role/text/task/activity strings) before constructing a full snapshot.
- Correction 2: LegacyChatHistoryImporter rejects raw payloads above 1,000,000 characters, more than 5,000 rows, and oversized role/text fields; malformed imports remain distinct from valid empty history.
- Correction 3: ChatPinsStore validates persisted pin sets (maximum 100 IDs, each nonblank and <=200 characters), rejects oversized IDs and capacity overflow, and preserves malformed persisted data rather than rewriting it.
- Regression tests cover over-capacity collections, oversized fields/payloads, and pin-set corruption/capacity without destructive writes.
- Starting commit: 3036187478d85bc3b900dc44162417df19bd287e. Push and PR Android Build passed; push and PR Gradle Wrapper Validation passed. APK, source and test-report artifacts were published. This package is not yet CI-verified; no device test and no 0.3 release claimed.


## CI correction — oversized whitespace-only legacy history — 2026-11-12
- CI for commit 2269ada71e2ca4c39b69b364085fbee3798cbc4f: Android unit tests failed 1 of 1129. The failing assertion was LegacyChatHistoryImporterTest.oversizedLegacyPayloadCollectionsAndFieldsFailClosed.
- Root cause: parse() checked isNullOrBlank() before raw payload length, so an oversized whitespace-only string was accepted as a valid empty history.
- Fix: distinguish null input, enforce MAX_RAW_CHARS first, then accept a within-limit blank string as empty history. Nonblank valid legacy history behavior is unchanged.
- This correction is awaiting CI; no test pass, APK release, or device validation is claimed.


## Follow-up package — canonical chat-history bounds — 2026-11-12
- Verified starting commit: `39457b72a4d3862aeeb8816b483c98a9e21d3488` on `work/aiforteca-0.2-master-agent-foundation`; all four Android Build / Gradle Wrapper Validation push and PR runs for the starting commit completed successfully.
- Correction 1: ChatHistoryStore now enforces the same 5,000-message, 32-character role, 32,000-character message, and 200-character chat-ID bounds during both persisted reads and writes.
- Correction 2: canonical chat history rejects more than 5,000 assistant task IDs on both read and write; existing duplicate/non-text checks remain in force.
- Correction 3: canonical chat history rejects more than 150 agent-activity rows and activity values longer than 32,000 characters on both read and write.
- Regression tests cover oversized persisted values and rejected writes while preserving malformed source bytes.
- CI for this package is pending until the new Android Build and Gradle Wrapper Validation push and PR runs finish. No APK release or device test is claimed.
