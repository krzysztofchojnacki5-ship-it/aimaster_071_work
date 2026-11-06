# AI MASTER PRO 0.6.102-SOURCE — uzgodnienie patcha po restarcie procesu

## Luka wykryta po 0.6.101
Android mógł zakończyć proces po zatwierdzeniu nowego `current/` + `current.manifest`, ale przed zwróceniem `ApplyResult` do `MainActivity`. Pliki były wtedy poprawnie zastosowane, lecz `ChangeSetStore` mógł nadal mieć tylko `APPROVED`, bez snapshotId/statusu/active pointer. Dziennik swapu chronił workspace, ale nie łączył transakcji z ChangeSet.

## Zmiany
- Wersja 2 `workspace.swap.journal` niesie metadane patcha: `changeSetId`, `chatId`, `snapshotId`, bazową identity oraz oczekiwany hash manifestu. Reader nadal obsługuje v1 z 0.6.101.
- Po pełnym commit workspace publikuje trwały `workspace.patch.receipt` (fsync + atomic move) z ID transakcji, ChangeSet/chat/snapshot, bazową i wynikową identity.
- Jeżeli proces zginie po instalacji pary katalog+manifest, ale przed publikacją receipt, recovery odtwarza receipt z dziennika.
- Jeżeli swap nie został zatwierdzony i poprzedni workspace został odtworzony, recovery zapisuje receipt `rolledBack=true`. Host nie pomyli przerwanego patcha z sukcesem.
- Receipt jest pojedynczy i musi zostać jawnie uzgodniony/acknowledged. Nowy patch jest blokowany, dopóki poprzedni receipt nie zostanie uzgodniony. Niepoprawny receipt blokuje operację fail-closed. Pruning snapshotów chroni snapshot wskazany przez pending receipt; przy uszkodzonym receipt pruning zostaje wstrzymany.
- `ApplyResult` przekazuje transactionId bez ponownego odczytu receipt po commit; pruning snapshotów jest niekrytyczny i nie może zmienić zatwierdzonego apply w fałszywy failure. Snapshot wskazany przez pending receipt jest chroniony przed pruningiem, także podczas późniejszego importu.
- Apply odrzuca no-op patch, którego wynikowy manifest ma identyczną identity jak baza; nie powstaje wtedy pozorny receipt sukcesu.
- `MainActivity` przekazuje ChangeSet/chat do `applyPreview()`, zapisuje/acknowledges receipt po normalnym ledger + active pointer flow oraz uruchamia odzyskiwanie receipt po przywróceniu aktywnego czatu przy starcie aplikacji.
- Startup recovery weryfikuje snapshot i jego manifest identity, ChangeSet ID, chatId oraz zgodność snapshotu. Idempotentnie odtwarza `recordSnapshot` + `APPLIED_UNVERIFIED` albo uzgadnia `REJECTED/ROLLED_BACK`. Active pointer jest odtwarzany tylko dla tego samego czatu i dokładnej bieżącej workspace identity.
- Jeżeli normalna kompensacja ma się rozpocząć, najpierw trwale zapisuje `rollbackRequested`, potem odtwarza snapshot i dopiero wtedy oznacza receipt jako `rolledBack`. Startup potrafi dokończyć żądany rollback po śmierci procesu; przy trzeciej identity blokuje się i nie nadpisuje nowszego projektu.
- Jeżeli proces zginął po restore, ale przed końcowym oznaczeniem receipt, startup rozpoznaje dokładną bazową identity i domyka rollback.
- Gdy operacja ma nierozliczony journal/receipt, `MainActivity` nie oznacza ChangeSet jako REJECTED tylko dlatego, że nie otrzymał `ApplyResult`.

## Testy lokalne
- `tools/workspace_transaction_recovery_harness/run.sh`: **12/12 PASS** — kompatybilność journal v1, commit/rollback crash states, odtworzenie brakującego committed receipt, trwały rollback intent + receipt, binding ChangeSet/chat/snapshot, base/output identity, blokada kolejnego patcha do ack, fail-closed uszkodzonego receipt.
- Workspace core `kotlinc -Werror`: **PASS**.
- Resolver/lock harness: **73/73 PASS**.
- ChangeSet/HostActive compile shim: **PASS** (`kotlinc -Werror`).
- Pełny Android/MainActivity compile nie został wykonany; parser bez classpath Android zgłaszał wyłącznie nierozwiązane zależności, nie raportował błędów składni.
- Gradle/JUnit/Robolectric/Android build/APK/device: **NOT RUN**.

## Granice
To jest recovery po stronie hosta uruchamiane przy starcie `MainActivity`, nie osobny systemowy worker. Jeśli SharedPreferences/ledger jest uszkodzony lub receipt nie zgadza się z rejestrem, receipt pozostaje, a patch nie jest ponawiany ani automatycznie uznawany za sukces. Companion transport pozostaje BLOCKED.
