# Existing Android architecture inventory — AiFORTeca 0.0.1
Date: 2026-11-09
Branch inspected: `work/aiforteca-0.0.1-foundation`
Inspection mode: read-only; no Kotlin source changed.

## Confirmed integration points

| Existing component | Observed responsibility | AiFORTeca contract |
|---|---|---|
| `MainActivity.kt` | Chat/UI entry point; initializes stores; coordinates provider selection, chat, work lifecycle and recovery. File is approximately 417 KB. | Thin UI boundary; move orchestration behind explicit interfaces in later stages. |
| `agents/TeamRunEngine.kt` | Runs specialist tasks concurrently (default maxParallel 4, clamped 1..8); provider routing/fallback; task state, usage, logs, cancellation scopes and result callbacks. `TeamTask` has `writeScope`; empty scope is read-only. | MASTER orchestrator, agent governance, bounded execution, evidence-bearing task results. |
| `core/MasterWorkJournalStore.kt` | Bounded task plans/progress/decisions in SharedPreferences; max 60 records, max 40 steps/decisions; statuses PLANNED/ACTIVE/BLOCKED/PAUSED/COMPLETED; pauses active work after process restart. | Recovery checkpoint and task-progress substrate; not yet a full append-only decision ledger. |
| `core/TaskCheckpointStore.kt` | Lifecycle event journal in SharedPreferences; max 800 events; preserves provider/model/token/idempotency metadata; protects terminal states from late callbacks and flags uncertain storage. | Recovery engine and execution evidence. |
| `core/TaskResultArchive.kt` | Durable bounded archive of successful specialist reports, addressable by task ID. | Evidence/artifact retention. |
| `core/ChatResponseArchive.kt` | Write-ahead archive for successful final chat responses until committed to chat history. | Crash-safe response delivery and recovery. |
| `core/QualityLearningStore.kt` | Stores ratings and learning proposals; only APPROVED proposals enter shared/agent memory; attempts rollback if memory writes fail. | Human-approved memory lifecycle and quality learning. |
| `core/ApiTokenTracker.kt` | Tracks provider/model/agent/task token usage and cost. | Resource/budget accounting and task evidence. |
| `data/SecureStore.kt` | Encrypted provider configuration storage and storage-health handling. | Secret boundary; must remain separate from learning/diagnostic records. |
| `network/ProviderClient.kt` | HTTP provider client, request scopes, provider errors and usage result integration. | Controlled model/tool transport; cancellation and error classification. |

## Architectural observations
1. There is already meaningful recovery infrastructure. On startup, MainActivity pauses active MASTER plans, checks journal/archive readability, recovers stale task states, and restores archived specialist reports and chat responses.
2. The system already distinguishes a recoverable task checkpoint from a confirmed result. Unknown provider usage is not silently recorded as zero; idempotency metadata is retained for recovery.
3. Learning is approval-gated. A proposal is not automatically promoted to shared/agent memory.
4. Agent task write ownership exists as a prompt-level declaration (`writeScope`). The inspected TeamRunEngine excerpt does not establish that this declaration is enforced by a filesystem sandbox; treat enforcement as UNVERIFIED until the execution/tool boundary is inspected.
5. Current durable stores inspected here are SharedPreferences + JSON, with explicit bounds and write-health handling. They are not a relational database or a general append-only event store.
6. MainActivity is a large orchestration/UI concentration point (~417 KB). AiFORTeca should introduce seams incrementally rather than rewrite it in one change.
7. A dedicated Decision Ledger and Error Knowledge Base implementation was not identified in the files inspected. This is NOT proof that no related code exists elsewhere in the repository.

## Integration gaps to resolve before Kotlin implementation
- Locate and map existing chat history, memory stores (`AiMemoryStore`, `AgentOperationalMemoryStore`), diagnostics, provider health/scheduler, peer-review and workspace/patch execution boundaries.
- Determine whether task IDs and chat IDs have stable uniqueness guarantees across import/restore.
- Inspect SharedPreferencesWriteHealth and store commit semantics; define how to migrate without discarding old data.
- Establish actual enforcement of TeamTask.writeScope before granting agents write-capable tools.
- Identify a safe transaction boundary for decision/error/package records and task checkpoints.
- Keep secrets and raw prompts/responses out of the proposed general-purpose ledgers unless separately approved and encrypted.

## Recommended implementation order
1. Extract interfaces around existing stores without changing storage behavior.
2. Add append-only DecisionLedger and ErrorKnowledgeBase with schema/versioning, provenance, bounded retention, and corruption-safe writes.
3. Add PackageLedger records tied to repository commit, exact build environment, test evidence, artifact checksum, and status.
4. Connect MASTER task lifecycle to QualityGate checks and persist each gate outcome.
5. Integrate RecoveryEngine with existing MasterWorkJournalStore/TaskCheckpointStore; avoid duplicate competing journals.
6. Enforce writeScope at the actual file/tool boundary, not only in the agent prompt.
7. Only after these seams and migration tests pass, reduce MainActivity orchestration responsibilities.

## Source evidence (blob SHAs)
- `MainActivity.kt`: `d2190a5280068692c04404bb6b2f13bc90e58aa8`
- `agents/TeamRunEngine.kt`: `5d30ec8056fd427cef5e92e66460e4f8eeb422fc`
- `core/MasterWorkJournalStore.kt`: `24ac256aa2419f9ebf7a50dc35e2932a1a1ab04e`
- `core/TaskCheckpointStore.kt`: `940f4a5e151fc752c23229219f4c2ec7cc2bb90e`
- `core/TaskResultArchive.kt`: `c4e1f026fd3805dbc275af9c1feb7aea6152d860`
- `core/ChatResponseArchive.kt`: `787977b42dd2c1b9f53c712c7e3eea3f85878246`
- `core/QualityLearningStore.kt`: `0bf00363a012a249151628ce43dd6d8046fc2c8c`
- `core/ApiTokenTracker.kt`: `e35d11d3d838924d721d0928f888c81557e3bce0`
- `data/SecureStore.kt`: `91692aebba0c52b608cf69df0493d49ef6c7f682`
- `network/ProviderClient.kt`: `93062cbcd7bb36ee8688c19a54dc9c21b1c0e852`

## Verification
This was source inspection only. No code was changed, no Gradle command was run, and no tests were executed.
