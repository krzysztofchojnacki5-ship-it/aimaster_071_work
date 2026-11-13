# AiFORTeca 0.2 — MASTER / Agent Control Center

## Release identity
- Android `versionName`: `0.2`
- Android `versionCode`: `3`
- Base commit: `9d91a3cdf4ea650f5027a175867fac01d1b95f1e` (AiFORTeca 0.0.2)
- Work branch: `work/aiforteca-0.2-master-agent-foundation`
- The 0.0.2 baseline remains unchanged and recoverable.

## Product priority
AiFORTeca is a MASTER-led agent orchestration workspace. The primary product surface is the coordination of MASTER, agents, tasks and shared memory. Provider/model administration is infrastructure for that system, not the home screen.

## 0.2 implementation scope
1. Open the app on the MASTER control center instead of the legacy chat screen.
2. Replace the legacy top-level tab row with department navigation:
   - MASTER
   - Zespół
   - Zadania
   - Pamięć
   - Komunikacja
   - Dostawcy
   - Diagnostyka
   - Czat
3. Preserve existing screens and local stores; navigation is reorganized, not destructive migration.
4. Add a task center that reads the existing durable MASTER work journal and task checkpoint journal.
5. Migrate only the known emulator-only default Ollama URL `http://10.0.2.2:11434/v1` to an empty, disabled endpoint on startup. Preserve model selection and every user-entered non-default endpoint. The user must enter their PC's actual private-LAN IPv4 address and explicitly opt into HTTP before a phone can connect.

## Existing foundations to reuse
- `TeamRunEngine`, `AgentCatalog`, `AgentManagerStore`: agent execution and management.
- `AgentProviderAssignmentStore`: durable role-to-provider assignments.
- `MasterWorkJournalStore`: bounded durable MASTER plans and progress.
- `TaskCheckpointStore`: task lifecycle and recovery journal.
- `AiMemoryStore`, `AgentOperationalMemoryStore`, `QualityLearningStore`: approved shared and agent-scoped learning.
- `ProviderClient`, `ProviderEndpointPolicy`, `SecureStore`: provider connectivity, endpoint validation and encrypted credentials.

## Non-destructive rules
- Do not delete or reset provider profiles, encrypted keys, chats, task journals, memories, quality history or agent definitions during UI work.
- Do not silently enable an agent or change its model assignment.
- Do not auto-resume interrupted work; keep the existing explicit recovery rules.
- Do not expose API keys or prompts in dashboards or logs.
- Do not treat a UI build as proof of working multi-agent execution.

## Acceptance gates before APK handoff
- Gradle wrapper validation.
- Full `testDebugUnitTest` suite.
- `assembleDebug`.
- Inspect APK metadata: package ID, versionName 0.2, versionCode 3.
- Inspect uploaded test reports and APK artifact.
- Manual device checks: launch to MASTER center; navigate all departments; verify task/memory screens read existing data; verify legacy emulator URL no longer triggers a network request; confirm user-configured LAN Ollama works only after explicit opt-in.
- Clearly distinguish automated tests from tests that require the user's physical phone and PC.

## Current checkpoint
Initial 0.2 source changes are committed on the work branch. CI/build status must be checked before any APK is called testable. No claim is made that physical-device Ollama connectivity or end-to-end agent orchestration has been manually verified.

## Required navigation departments
The 0.2 shell must expose nine distinct destinations: MASTER, Zespół agentów, Zadania, Pamięć, Komunikacja, Dostawcy i modele, Diagnostyka, Ustawienia and Czat. Ustawienia is a separate department, not a substitute for provider configuration or diagnostics.

## Data governance contract — required, not yet claimed as implemented
This is a release requirement for the memory/configuration lifecycle. Existing stores must be audited and brought under the contract incrementally; a UI label or prompt instruction alone is not sufficient.

### Default authority model
- Agents are read-only by default outside their explicitly assigned task scope.
- An agent may create or update only records in its authorized scope and must identify the source task and actor.
- Agents cannot delete shared memory, conversation history, provider credentials, provider profiles, agent definitions or global configuration.
- Destructive operations and changes to security-sensitive configuration require explicit user approval or a separately recorded MASTER approval policy. A model's natural-language claim is not approval.
- MASTER may propose and coordinate mutations, but it cannot bypass host-enforced permissions or approval gates.

### Audit trail and provenance
Every persisted mutation must record: event ID, timestamp, actor/agent ID, operation (CREATE/UPDATE/DELETE), entity/store, source task or conversation, approval reference when required, outcome, and a redacted before/after representation or cryptographic digest. Never place API keys, tokens, private prompts or other secrets in the audit trail. The audit log must be append-only from the agent-facing API; agents cannot rewrite or erase their own audit history.

### Memory lifecycle
New shared memories enter a pending state with source/provenance and proposed scope. Approval promotes them to shared/active memory. Rejected or superseded records remain traceable; updates create a new version rather than silently overwriting provenance. Deletion is a separately authorized tombstone/retention operation, never an ordinary agent write.

### Failure and recovery semantics
- Validate authorization and approval before mutation.
- Persist the mutation and its audit event as one transaction where the backing store permits; otherwise use a recoverable journal with explicit commit states.
- On uncertain storage, corrupted journal or failed commit, fail closed: do not publish a success state and do not silently overwrite the prior record.
- Preserve prior versions and support inspection/recovery. No startup cleanup may remove user data without a separately approved retention rule.

### Acceptance tests for this contract
Cover unauthorized create/update/delete, scope escape, missing or forged approval, audit tampering, duplicate/replayed requests, concurrent updates, interrupted commits/recovery, secret redaction, and preservation of legacy records. Tests must exercise the real store adapters, not only prompt text or UI.

## Execution checkpoint — 2026-11-10
- The failed Android workflow run 42810366053 was tied to the earlier source state at commit 77db75dce6713347895dd8efa19f002bb9a6d70c. Its Kotlin compiler error was caused by five drawer-row statements joined with literal escaped newline text on one source line.
- The current branch source has those statements on separate physical lines; the previously failing pattern is absent.
- Added the dedicated Ustawienia department and drawer entry.
- Added the data-governance contract to the plan. The enforcement layer is still pending implementation and must not be reported as complete.
- Current known branch commit after the source and plan updates: 763868aa300e9918bfee85035be03fa3a5e3b693.
- The previous wrapper-validation workflow passed, but the Android compile failed. No newer workflow run, successful Android build, unit-test result, APK artifact or device test has been confirmed for the current branch state.
- Local execution is unavailable in this environment because Gradle and Android SDK are not installed and outbound DNS/network access is unavailable. Do not mark build/tests as PASS on that basis.
- Release identity remains versionName=0.2, versionCode=3.

## Implementation checkpoint — MASTER workspace increment 1
- Added `MasterTaskQueueStore`: a separate durable queue for operational tasks; the older `MasterWorkJournalStore` and `TaskCheckpointStore` remain intact and are not migrated or cleared.
- Queue records support bounded task descriptions, priority, dependencies, multiple agent assignments, explicit lifecycle states and a bounded transition history in the same persisted JSON document.
- Coordinator operations are restricted to `MASTER`/`USER`; an agent identity may report only its own assigned RUNNING task as COMPLETED/FAILED/BLOCKED. An agent cannot assign work, pause/cancel work or change another agent's task.
- Assignment validates that every selected agent is currently enabled. Dependencies must already be completed before a dependent task is created or assigned.
- Corrupt or uncertain queue storage fails closed; it is not replaced with an empty queue. Terminal states cannot be reopened through ordinary transitions.
- The Zadania screen now creates durable queue items, assigns enabled agents, displays task state and transition history, and supports pause/unblock/cancel without deleting task records.
- **Explicit limitation of this increment:** the new queue is not yet connected to `TeamRunEngine`; assignment currently records ownership and status only. The screen does not claim that an LLM call has started. Connecting queue execution to the existing Smart Budget / `TeamRunEngine` path, and routing its callbacks into the queue, is the next implementation gate.
- Added unit tests for queue persistence, assignment authorization, disabled agents, dependency gating, terminal-state protection and corrupt-storage fail-closed behavior.
- CI status must be read from the workflow run for the latest branch head before this increment is called build/test verified. No physical-device test has been performed for the new queue UI.

## Preserved follow-on backlog (do not drop)
1. Connect MASTER task queue to the existing adaptive planning, Smart Budget and `TeamRunEngine`; persist QUEUED/ASSIGNED/RUNNING/terminal transitions from actual execution callbacks and support safe cancellation/recovery.
2. Build the Android-Studio-style workspace layout: navigation/project pane, central task/agent workspace, contextual inspector, and bottom event console; adapt panels for phone screens.
3. Add live agent-to-agent communication stream with sender, recipient, task, model/provider, timestamp, status and sanitized payload preview.
4. Add governed memory/configuration mutation adapters: per-agent read/write/update/delete scopes, MASTER/user approvals, append-only provenance/audit, version history, tombstones and fail-closed recovery. Enforce in storage adapters, not only UI or prompts.
5. Add diagnostics/console view aggregating queue events, task checkpoints, provider health, budget, errors and persistence health; redact secrets and prompts.
6. Add agent workload/availability, role/model assignment visibility, task dependencies, priorities, conflict detection and duplicate-work prevention.
7. Complete regression and acceptance tests against real store adapters; run CI, inspect test reports/APK metadata, then prepare a new test APK while preserving version 0.2 until the user authorizes a version bump.
8. Preserve all existing provider profiles, encrypted keys, chats, memories, learning history, agent definitions and legacy journals; no destructive migration without explicit approval.


## Implementation checkpoint — package 15 / explicit agent handoff rejection (2026-11-13)
- Package 14 is closed: the latest verified commit before package 15 was `d0081813e4c0136cad76ec50aea3cd0c8fa0f64d`; Gradle Wrapper Validation and AiFORTeca Unit Tests both completed successfully. The published test report recorded 1,194 tests, 0 failures, 0 errors and 0 skipped tests.
- Package 15 is in progress and is scoped to the explicit durable agent-to-agent handoff contract. The first implementation increment adds `AgentHandoffStore.reject(...)`: only the addressed, assigned recipient may reject its own CLAIMED handoff while the exact parent run is active.
- Rejection persists a sanitized, bounded audit reason, preserves the claim timestamp, advances the update timestamp, and is idempotent only for the same sanitized reason. Rejected messages cannot subsequently be claimed or acknowledged.
- Persisted REJECTED records now require a non-empty sanitized audit reason and valid monotonic timestamps; malformed rejection records fail closed and their original stored bytes remain untouched.
- Added tests for recipient authorization, secret redaction, duplicate rejection, rejection timestamp rollback, terminal-state behavior and malformed persisted REJECTED records.
- Source commit: `d301d18af014f5c8e11ea8e3f4d504108ced3e62`; follow-up test commits: `b34a76f62ae8759adf29810658f4e30a5c107674`, `b3a5eb2429efc0ff70b07e7e6c3a3b48e092f423`, `bba6b315c8f0cdfd623db1484a185de833013505`.
- The initial implementation increment established the rejection transition and focused test hardening. The five listed package-15 corrections are now implemented; the final CI run for HEAD `e88e4b2b523f08cadada677bf10824fb59c25759` passed, with 1,195 tests and no failures, errors or skips.


### Package 15 — five implementation corrections completed and CI verified
1. Added an explicit recipient-only rejection transition for a claimed handoff.
2. Required a non-empty, sanitized, bounded rejection reason in persisted REJECTED records; malformed records fail closed.
3. Made rejection retries idempotent only when the sanitized reason matches the already stored decision.
4. Enforced claim-to-rejection timestamp monotonicity; an older timestamp cannot close the claim.
5. Exposed rejected handoffs in Diagnostics with a count and recent audit entries, rather than silently omitting the new terminal state.
- Focused unit tests cover authorization, redaction, duplicate retries, backwards timestamps, terminal-state protection and malformed persisted rejection records.
- Source and tests are committed. At code HEAD `e88e4b2b523f08cadada677bf10824fb59c25759`, Gradle Wrapper Validation and AiFORTeca Unit Tests both passed; the XML artifact records 1,195 tests, 0 failures, 0 errors and 0 skipped. The follow-up commit `869495cb7aad291fe67ffcc07bea922929b8a33b` updates this documentation only.
