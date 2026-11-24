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
- Package 15 is closed and CI-verified. It delivered the explicit durable agent-to-agent handoff rejection store contract: only the addressed, assigned recipient may reject its own CLAIMED handoff while the exact parent run is active.
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

## Implementation checkpoint — package 16 / strict agent handoff rejection protocol (2026-11-13)
- Base checkpoint: `272622b6b88a431f3b173bbe6b3f7587cc4aee86` (package 15 documentation reconciled after CI).
- Five implementation corrections are now committed on the work branch:
  1. Added the reserved `AIFORTECA_HANDOFF_REJECT_V1:` protocol with strict single-object JSON parsing; ordinary report prose is not interpreted as a rejection, and malformed explicit directives fail closed.
  2. The protocol is offered only to agents that actually receive an inbound handoff; parsed rejection results carry a sanitized reason and `BLOCKED` status.
  3. The MASTER queue persists explicit recipient rejection through `AgentHandoffStore.reject(...)` and records a `HANDOFF_REJECTED` technical trace instead of acknowledging the handoff.
  4. A rejected handoff does not create an outbound handoff; downstream agents with no available inbound handoff are classified `BLOCKED` before an API request.
  5. Added network-level regression tests for a valid rejection, malformed directives, ordinary prose containing the marker, and stopping the downstream chain.
- Relevant commits: `273e862602ed5d0fb8e4cedf1f4295fe04cd26cf`, `84d3bc51925ebbd869a0c60aee6da0299ec4c18`, `9d8c1db0ab1b338d2ef38b9e0f4ed5ff9fc3d729`, `9f36661dd677e9ab6c9a541b1b181beffda80736`, `7bd72de2394331b2583ccab149d1a4f690e09569`, `a438af3e05350bbadf8dfcb348cfda9a73b479db`, `7abd230665f8fef83c0582544e4bb105c911e150`, `0dd23beaae1a2b3f50d5762e40054690dd0fdd33`.
- Package 16 CI is verified on code HEAD `fe2e48c940f656e7749e7b953d17f9700b7c2451`: Gradle Wrapper Validation run `43397835339` passed; AiFORTeca Unit Tests run `43397835360` passed. The uploaded XML artifact `14023781730` contains 1,197 tests across 113 XML files, with 0 failures, 0 errors and 0 skipped tests.
- CI exposed a pre-existing network-test fixture gap: the simulated HTTP 500 is an ambiguous POST outcome and must be durably recorded as unknown usage before the task can be classified FAILED. The test now supplies an explicit successful `onUnknownUsage` recorder and asserts exactly one such record; it does not weaken production accounting behavior.
- Package 16 is closed as CI-verified. No Android APK build is claimed for this package; the scheduled full Android build remains aligned with the agreed 20-fix cadence.

## Implementation checkpoint — package 17 / atomic queue cancellation fencing (2026-11-13)
- Base checkpoint: `fe2e48c940f656e7749e7b953d17f9700b7c2451` (package 16 CI-verified).
- Five corrections implemented:
  1. Queue lifecycle callbacks now enter an exact task/run active fence before writing checkpoints or agent progress; stale/cancelled callbacks are ignored.
  2. Agent-result finalization and missing-agent failure recording execute inside one active-run critical section, serialized against cancellation.
  3. Added store-level regression coverage proving stale run IDs and cancelled runs cannot execute fenced side effects.
  4. Moved durable result archival inside the active-run fence, preventing a late result from being added to the archive after cancellation or a retry.
  5. Fenced agent communication/technical trace persistence against the same active task/run, so late callbacks cannot append stale events to the queue timeline.
- Relevant commits: `0232a6f68aa5f5f398c0796378e8e2444fe5e9da`, `3cb979f267c6eb5a9947577355340ab90c02507f`, `e65b72ba9cf7455a52ecc4c92e87db760865750d`, `1054f963400b02376d531c09ff2f269ae0c37977`.
- Regression tests cover stale/cancelled side-effect rejection, cancellation serialization, late progress/message/trace/result rejection and retry run-epoch isolation.
- Package 17 CI is verified on HEAD `a644e18a49115e6168888e26b5e858fb099f44d6`: Gradle Wrapper Validation run `43409560488` passed; AiFORTeca Unit Tests run `43409560492` passed. Artifact `14029251587` contains 1,199 tests across 113 XML files, with 0 failures, 0 errors and 0 skipped tests. Package 17 is closed as CI-verified. No Android APK build is claimed; full Android build remains on the agreed 20-fix cadence.


## Implementation checkpoint — package 18 / archived report metadata redaction (2026-11-13)
- Base checkpoint: `baf7b3eae7ade8cebce02ac3622112cd65943a2f` (package 17 CI-verified).
- Five corrections implemented:
  1. Sanitize agent identity labels before writing archived report records.
  2. Sanitize provider identifiers before persistence, preventing credential-like metadata from entering the archive.
  3. Sanitize model labels before persistence for the same reason.
  4. Treat chat IDs as immutable lookup keys: reject an ID if sanitization would change it, rather than silently attaching a report to another chat.
  5. Sanitize agent/provider/model labels again during replay, protecting chat history when older archive rows contain unsanitized metadata.
- Added five regression tests covering metadata redaction, unchanged identity keys, unsafe chat-key rejection and legacy archive replay.
- Relevant commits: `2cbf7f60843ce0170110069f2b40476cce7eae8d`, `c5f2501c673ab5ec56349e91e985f7d686a6dec3`.
- Package 18 is closed as CI-verified on branch HEAD `1dd293f090d860b60bc71d07edbc261910b280a0` (source/test commit `c5f2501c673ab5ec56349e91e985f7d686a6dec3`; documentation checkpoint commit `1dd293f090d860b60bc71d07edbc261910b280a0`). Gradle Wrapper Validation run `43410271783` and AiFORTeca Unit Tests run `43410271908` both completed successfully on the latest HEAD. The uploaded XML artifact `14029905216` contains 1,204 tests across 113 XML files, with 0 failures, 0 errors and 0 skipped tests. The unit-test job completed its `Run unit tests` and `Upload unit test reports` steps successfully. No Android APK build is claimed; full Android build remains on the agreed 20-fix cadence.

## Implementation checkpoint — package 19 / queue lifecycle, memory scope and durable workspace patching (2026-11-13)
- Base checkpoint: package 18 CI-verified code HEAD `1dd293f090d860b60bc71d07edbc261910b280a0`.
- Four corrections completed:
  1. Hardened MASTER queue lifecycle transitions and persisted event metadata, including timestamp monotonicity and event-history retention.
  2. Added `REQUESTING` to durable agent-progress states so the queue records the real request phase instead of rejecting it.
  3. Bound shared-memory store permits to exact object IDs and aligned the Windows test fixture with the supported filesystem behavior.
  4. Replaced unsafe staged workspace patch deletion/rename with the durable atomic publisher; failed replacement preserves the previous target.
- Relevant code commits: `611c081a5aa8f3019afc0f4f8f5422185b827a78`, `4feba84750e9649d2a7950c5df99b4ce16583bb0`, `7af13d7a6d5baebbc00c48c8a22c043c1d647626`, `d157d7e2a70d3281e11d149e5c8099e1136d5399`.
- Package 19 CI verified on code HEAD `d157d7e2a70d3281e11d149e5c8099e1136d5399`: Gradle Wrapper Validation run `43469109567` passed; AiFORTeca Unit Tests run `43469109432` passed. XML artifact `14059249881` records 1,210 tests across 113 XML files, with 0 failures, 0 errors and 0 skipped tests.
- No Android APK build is claimed for this package.

## Implementation checkpoint — package 20 / fail-closed trust durability and exact memory provenance (2026-11-13)
- Base checkpoint: package 19 CI-verified code HEAD `d157d7e2a70d3281e11d149e5c8099e1136d5399`.
- Four corrections completed:
  1. Companion trust marker directory-fsync failures now propagate; publication/deletion is not reported as durable when directory synchronization fails.
  2. Shared-memory mutation APIs require a nonblank exact object ID and the manual-memory UI passes the same ID that was authorized. Regression coverage rejects cross-object permit reuse.
  3. Agent operational-memory writes/removals are bound to the exact proposal/source ID. Proposal writes must carry their matching `proposal:<id>` provenance, and direct agent lessons cannot forge proposal provenance.
  4. Persisted queue task/event metadata is rejected fail-closed if it contains secret-like patterns; custom agent IDs/names are also screened before they can enter event metadata. Original corrupt bytes are preserved.
- Relevant code commits: `ae3b4810336a5a41726849e9c89b600bed547744`, `d6e195edb25bfea5bd510c9c516c65407f9d96e2`, `f4e1dab8cbd51fe4fdfa56c3d0cb136eec1678a6`, `55db41ccef9266ebeee32fdc56b813d42bd5294c`.
- An intermediate CI compile failure on `d6e195e` exposed two omitted test call-site updates (missing object ID and nullable assertion). Both were corrected in `f4e1dab`; no unresolved compiler failure remains in the package-final commit.
- Package 20 CI verified on code HEAD `55db41ccef9266ebeee32fdc56b813d42bd5294c`: Gradle Wrapper Validation run `43472733905` passed; AiFORTeca Unit Tests run `43472733568` passed. XML artifact `14060203942` records 1,213 tests across 113 XML files, with 0 failures, 0 errors and 0 skipped tests.
- Android `assembleDebug`, APK metadata inspection and physical-device checks have not been run in this package; the Android Build/APK cadence remains separate from the unit-test CI cadence.
- Pull request #3 remains open. Work branch: `work/aiforteca-0.2-master-agent-foundation`; latest code HEAD at this checkpoint: `55db41ccef9266ebeee32fdc56b813d42bd5294c`.



## Implementation checkpoint — package 21 / reliable conversation context (2026-11-19)

- Base code HEAD: `a46d33512cd9bfd5c93406f59c93a94e96b35a56`.
- Final code HEAD: `fb8d4b6d10526f1107a164827ae6795069d10351`.
- Four logical corrections delivered:
  1. Added `ConversationContextBuilder`: only visible `user`/`assistant` turns are accepted; internal/system/tool events are excluded; the most recent context is retained under a 12,000-character history budget; a pending duplicate current user turn is removed.
  2. Added provider-specific payload mapping and connected it to all four transports: OpenAI-compatible, Anthropic, Gemini OpenAI-compatible and Gemini Native. Gemini Native now uses `systemInstruction` separately and maps assistant turns to `model`.
  3. `MainActivity` snapshots completed turns for the exact active chat before starting the worker. The pending message is excluded from the snapshot. The immutable snapshot follows the request even if the user switches chats; no global chat history is read by the worker. History is supplied to direct MASTER/selected-agent requests and the final MASTER synthesis, but not fanned out to specialist tasks.
  4. Added regression tests for ordering, role filtering, duplicate suppression, context bounds, all provider payload shapes, Gemini role mapping and attachment isolation. History length is included in request budget estimates and local usage input accounting.
- Files changed: `MainActivity.kt`, `ProviderClient.kt`; added `ConversationContextBuilder.kt`, `ProviderConversationPayloads.kt`, `ConversationContextBuilderTest.kt`, `ProviderConversationPayloadsTest.kt`.
- Initial CI attempt run `44552018355` exposed a test-fixture issue: four JSON payload tests ran against the Android stub `org.json` implementation without Robolectric. The production Kotlin sources compiled. The tests were corrected to use `RobolectricTestRunner`; no production code was weakened to accommodate the fixture.
- Final verification on code HEAD `fb8d4b6d10526f1107a164827ae6795069d10351`:
  - `AiFORTeca Unit Tests` run `44552837544` — SUCCESS.
  - XML artifact `14542654144`, SHA-256 `55b5de62630e23c6c6045f12fd536e18c806c906c782f090c0786166da9a5213`.
  - 1,342 tests across 125 suites; 0 failures, 0 errors, 0 skipped.
  - `Gradle Wrapper Validation` run `44552837439` — SUCCESS.
- No `assembleDebug`/APK artifact or emulator/device test was run by this unit-test workflow. APK build and manual conversation-retention verification remain pending.
- Gradle 9.9.0 and AGP 9.4.0 were not changed. PR #3 remains open in Draft state.


## Implementation checkpoint — pakiet 22 / konsola diagnostyczna na żywo (2026-11-20)

- Baza: branch HEAD `7e7ae1e025eb17c7cb9753a30f18566e3fa343f5` (pakiet 21 i pełny Android Build #758 zweryfikowane osobno).
- Zakres dokładnie czterech zmian: (1) filtr źródła `QUEUE`/`HANDOFF` w `DiagnosticsEventFeed`; (2) widoczne filtry ID zadania, agenta, dostawcy/modelu, operacji i źródła wraz z zastosowaniem/czyszczeniem; (3) automatyczne odświeżanie samego dziennika co 5 sekund, bez przebudowy ekranu i skoku przewijania; (4) lifecycle fencing odświeżania — stop przy zmianie działu, `onPause` i `onDestroy`, wznowienie po `onResume`.
- Test regresyjny dodany dla filtra źródła; istniejące testy ochrony payloadów/sekretów pozostają bez osłabienia.
- Zmodyfikowane pliki: `MainActivity.kt`, `DiagnosticsEventFeed.kt`, `DiagnosticsEventFeedTest.kt`.
- Weryfikacja lokalna: `./gradlew testDebugUnitTest --no-daemon` NIE WYKONANA — wrapper nie zdołał pobrać Gradle 9.9.0 z powodu `UnknownHostException: services.gradle.org`. Nie jest to wynik testów ani błąd kompilacji źródeł.
- Status pakietu 22: IMPLEMENTACJA PRZYGOTOWANA, CI PENDING; nie oznaczać jako zaliczonego, dopóki testy i wrapper validation nie zakończą się sukcesem na dokładnym HEAD.
- Nie zmieniono `versionName=0.2` ani `versionCode=3`; nie zmieniono base branch, nie wykonano merge.
- Następny krok po CI: sprawdzić raporty testów i ewentualne poprawki; dopiero po zielonym CI zamknąć pakiet 22. Następny priorytet pozostaje przegląd pełnego magazynu eventów/diagnostyki i kompletności governance, bez deklarowania całej konsoli jako kompletnej.

## Implementation checkpoint — pakiet 23 / obciążenie agentów i wykrywanie konfliktów (2026-11-24)

- Zakres rozbudowy: rozszerzono istniejący, tylko-odczytowy `MasterOperationsOverview`; nie dodano nowego magazynu i nie zmieniano danych użytkownika.
- Cztery logiczne zmiany:
  1. Projekcja obciążenia każdego agenta z aktywnej kolejki: QUEUED, ASSIGNED, RUNNING, BLOCKED i PAUSED, z rozdzieleniem liczników i identyfikatorów zadań.
  2. Wykrywanie konfliktów przydziałów: jeden agent w wielu zadaniach RUNNING oraz agent wyłączony, który nadal ma aktywne przydziały.
  3. Wykrywanie możliwych duplikatów wyłącznie dla aktywnych zadań z identycznym, znormalizowanym celem o długości co najmniej 40 znaków; zadania terminalne i krótkie ogólne cele są pomijane.
  4. Rozszerzenie Centrum MASTER o obciążenie, dostępność, konflikt, duplikaty oraz ostatni status i provider/model agenta. Telemetria jest wiązana z dokładnym taskId i runId; nie pokazuje swobodnego payloadu zdarzeń.
- Katalog agentów i kolejka są odczytywane fail-closed. Niedostępny/uszkodzony katalog nie jest prezentowany jako pusty lub w pełni dostępny zespół; niedostępna kolejka nie jest traktowana jako brak obciążenia.
- Dodano regresje dla obciążenia stanów, konfliktów, wyłączonego agenta z przydziałem, deduplikacji celów, braku danych katalogu oraz izolacji telemetrii między zadaniami i epokami run.
- Zmodyfikowane pliki: `MasterOperationsOverview.kt`, `MainActivity.kt`, `MasterOperationsOverviewTest.kt`.
- Zmiany źródłowe zapisano na wskazanej gałęzi. Pełne CI dla końcowego HEAD pakietu 23 pozostaje do odczytu po zakończeniu automatycznie uruchomionych workflow; do tego czasu pakiet ma status IMPLEMENTACJA ZAPISANA / CI PENDING.
- Nie zmieniono identyfikacji wydania (`versionName=0.2`, `versionCode=3`), base branch ani PR. Nie wykonano ręcznego ponownego uruchomienia workflow ani budowy APK.


## Implementation checkpoint — pakiet 24 / deterministyczny planista i bezpieczny przydział MASTER (2026-11-24)

- Baza: pakiet 23 zweryfikowany na HEAD `dc4d5602dfd75e0849865279607b8026f618d3df`; Unit Tests run `45435510527` PASS (1,408 testów, 0 failures/errors/skipped), Gradle Wrapper Validation run `45435510246` PASS.
- Cztery logiczne zmiany pakietu:
  1. Dodano czysty, deterministyczny `MasterAgentCoordinator`: ranking według dopasowania kompetencji, obciążenia, stabilnego tie-breaku oraz limitu agentów.
  2. Planista odrzuca wyłączonych agentów, agentów bez potwierdzonej gotowości dostawcy i agentów na limicie obciążenia; nie tworzy dopasowania, gdy brak dowodów kompetencji. Gotowość dostawcy jest wejściowym snapshotem dostarczanym przez koordynatora — planista nie udaje aktywnego testu sieci.
  3. Dodano atomową ścieżkę `MasterTaskQueueStore.assignPlanned`: wymaga zgodności `updatedAt/runId`, blokuje długie identyczne cele w aktywnych zadaniach oraz przydział agenta ponad limit. Walidacja i zapis odbywają się pod blokadą kolejki.
  4. Dodano testy regresyjne rankingu, tie-breaku, wyłączeń, braku gotowego providera, przeciążenia, braku dopasowania, nieprawidłowego snapshotu, duplikatów zadań i nieaktualnego planu.
- Zmodyfikowane pliki: `MasterAgentCoordinator.kt`, `MasterAgentCoordinatorTest.kt`, `MasterTaskQueueStore.kt`, `MasterTaskQueueStoreTest.kt`, niniejszy checkpoint.
- Commity pakietu: `a9c99c1e2edffb137e25d90e84439b5947eda07d`, `32837c33c489c004f109139cd81111385d27b904`, `7b6e1bd719da5efb9bb64093d112bf3f722faef3`, `7a1e7e200d4defdd06912bf56af639ed5d36c3c2`.
- Status: IMPLEMENTACJA ZAPISANA / CI PENDING. Nie deklarować PASS przed zakończeniem workflow i odczytem raportów XML.
- Ograniczenie zakresu: pakiet dostarcza planistę i atomowy kontrakt zatwierdzania przydziału; nie podłącza jeszcze automatycznego uruchamiania zadań z kolejki ani aktywnego health-check providera. To pozostaje kolejnym krokiem integracyjnym po weryfikacji tego pakietu.
- Nie zmieniono `versionName=0.2`, `versionCode=3`, base branch ani PR #3. Nie uruchomiono ręcznie workflow ani budowy APK.


## Implementation checkpoint — pakiet 25 / integracja planisty z routingiem czatu MASTER (2026-11-24)

- Baza: pakiet 24 na HEAD `e8b6752caf1eef878bd6131ab4705d6981f85afb` zweryfikowany: Unit Tests run `45437223468` i `45437239046` PASS; raport XML: 1,415 testów, 0 failures/errors/skipped; oba przebiegi Gradle Wrapper Validation PASS.
- Cztery logiczne zmiany pakietu:
  1. `MasterAgentCoordinator.planFromSnapshots` buduje deterministyczny obraz obciążenia z aktywnych przypisań kolejki i odrzuca niepoprawny snapshot.
  2. Główny przepływ czatu MASTER pobiera snapshot `MasterTaskQueueStore` i przekazuje aktywne przypisania do planisty; obciążony agent nie zostaje wybrany ponad limit jednego aktywnego zadania.
  3. Przed delegacją sprawdzana jest gotowość przypisanego dostawcy agenta oraz dozwolony fallback MASTER-a. Brak czytelnej kolejki, gotowego dostawcy lub kwalifikowanego agenta powoduje bezpieczny powrót do MASTER-only — bez pustego uruchomienia zespołu.
  4. Dodano testy liczenia obciążenia z wielu aktywnych zespołów i fail-closed dla uszkodzonego snapshotu.
- Zmodyfikowane pliki: `MasterAgentCoordinator.kt`, `MainActivity.kt`, `MasterAgentCoordinatorTest.kt`, niniejszy checkpoint.
- Commity kodu pakietu: `ae024ed17bcf60383ee724a8b6472de97c910acc`, `e4d4cc4ff94725d58b3ac14153ff5859060bd062`, `2f075164c2af90a40454a74cbd466553e5125027`, `e7514d3da348b4e074cf6b2c0f639ef18ed45d63`, `9d6631ef35d390f30a6bd0d7b3d8f19e204d5f9f`, `cb4e626fd3fe78bd88d318bcdca5b09934af3ae3`.
- Weryfikacja końcowego SHA kodu `cb4e626fd3fe78bd88d318bcdca5b09934af3ae3`: Unit Tests push run `45466353925` PASS i PR run `45466367743` PASS; oba raporty XML mają 1,418 testów, 0 failures, 0 errors, 0 skipped (132 pliki XML każdy). Gradle Wrapper Validation push `45466354116` PASS i PR `45466367541` PASS. Log Gradle potwierdza `BUILD SUCCESSFUL`.
- Artefakty raportów: `14955038615` (push) oraz `14954584963` (PR); oba pobrane i policzone niezależnie.
- Status: PAKIET 25 ZALICZONY — CI, testy, wrapper i artefakty zweryfikowane na końcowym SHA kodu.
- Granica zakresu: planista jest teraz używany w rzeczywistym routingu wiadomości czatu, ale trwałe tworzenie i finalizacja rekordów kolejki dla każdego uruchomienia zespołu oraz automatyczne zastępstwa agentów pozostają osobnym etapem integracji. `assignPlanned` pozostaje atomowym API kolejki i nie jest jeszcze wywoływane przez przepływ czatu.
- Nie zmieniono repozytorium, gałęzi, PR #3, base branch, numeru wersji ani ustawień workflow. Nie uruchomiono ręcznie CI ani budowy APK.


## Implementation checkpoint — pakiet 26 / automatyczny przydział zadań kolejki przez MASTER (2026-11-24)

- Baza: pakiet 25 zaliczony na kodowym SHA `cb4e626fd3fe78bd88d318bcdca5b09934af3ae3`: dwa przebiegi Unit Tests PASS, oba raporty XML po 1,418 testów i 0 failures/errors/skipped; oba przebiegi Gradle Wrapper Validation PASS. Dokumentacyjny HEAD `320ae8a8f46e38174b1104c7762caacbc14cead5` różni się od testowanego SHA wyłącznie plikiem planu.
- Cztery logiczne zmiany pakietu:
  1. Dodano `MasterQueueAssignmentPlanner`, który buduje propozycję dla konkretnego zadania kolejki, zachowuje dokładne `updatedAt/runId` i odrzuca niedostępną kolejkę, brak zadania lub niewłaściwy stan.
  2. Centrum Zadań otrzymało akcję „MASTER — DOBIERZ AGENTÓW”. MASTER uwzględnia aktywne przydziały, dopasowanie kompetencji i dostawców gotowych oraz dozwolonych przez politykę kosztów; użytkownik zatwierdza propozycję przed zapisem. Ręczny wybór agentów również korzysta teraz z tego samego atomowego kontraktu, więc nie omija limitów obciążenia.
  3. Zatwierdzenie korzysta z atomowego `MasterTaskQueueStore.assignPlanned`, które ponownie sprawdza snapshot, duplikat celu i limit obciążenia. Naprawiono też nadmierną blokadę krótkich celów: heurystyka duplikatów pomija cele krótsze niż 40 znaków zamiast odrzucać każdy taki przydział.
  4. Dodano testy planowania z dokładnym snapshotem kolejki, obciążonym agentem, niedostępnym providerem, niedostępną kolejką, niewłaściwym stanem zadania oraz krótkim celem.
- Zmodyfikowane pliki: `MasterQueueAssignmentPlanner.kt`, `MasterQueueAssignmentPlannerTest.kt`, `MainActivity.kt`, `MasterTaskQueueStore.kt`, `MasterTaskQueueStoreTest.kt`, niniejszy checkpoint.
- Commity kodu pakietu: `0a1c97a1a557e8b0ce83551531b95f54ae44bc4f`, `9c883a15b5e01d2e7c6b6940f78c2df94334a0e3`, `235d40c73af600579da809b065d7aaab6e8a942e`, `0a3169edd85e1f6d62391406c2629b70892413a6`, `88e184f1dfda6ccd4837683d18b42310d1c173d6`, `62f813c0f5bc12781119c2b7beca164a940d8ca1`.
- Weryfikacja końcowego SHA kodu `62f813c0f5bc12781119c2b7beca164a940d8ca1`: Unit Tests push run `45467863037` PASS i PR run `45467879536` PASS; oba raporty XML mają 1,422 testy, 0 failures, 0 errors, 0 skipped (133 pliki XML każdy). `MasterQueueAssignmentPlannerTest`: 3/3 PASS; `MasterTaskQueueStoreTest`: 59/59 PASS, w tym test krótkiego celu.
- Gradle Wrapper Validation push `45467863107` PASS i PR `45467879823` PASS. Oba logi testowe potwierdzają `BUILD SUCCESSFUL`.
- Artefakty raportów: `14955608471` (push) oraz `14957175583` (PR); oba pobrane i sprawdzone.
- Status: PAKIET 26 ZALICZONY — CI, testy, wrapper i artefakty zweryfikowane na końcowym SHA kodu.
- Granica zakresu: przydział automatyczny jest teraz dostępny dla zadań w Centrum Zadań i używa istniejącego silnika wykonawczego po zatwierdzeniu. Automatyczne zastępowanie agenta po błędzie/429 oraz wznowienie przez alternatywnego specjalistę pozostaje osobnym etapem.
- Nie zmieniono repozytorium, gałęzi, PR #3, base branch, numeru wersji ani ustawień workflow. Nie uruchomiono ręcznie CI ani budowy APK.
