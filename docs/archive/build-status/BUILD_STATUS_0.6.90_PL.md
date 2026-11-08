# Build status — 0.6.90-SOURCE

- Parent: `AI_MASTER_PRO_0_6_89_MASTER_EVIDENCE_SELF_CHECK_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: powiązać typowane dowody z dokładnym zakresem zadania, czatu i snapshotu; odciąć możliwość przypadkowego mieszania dowodów z różnych kontekstów.

## Zmiany

- `VerificationEvidence` otrzymał pola `chatId` i `snapshotId`; istniejące `taskId` pozostaje identyfikatorem zadania.
- `VerificationAssessment.assess()` odrzuca mieszanie dowodów z różnych taskId, chatId lub snapshotId.
- `VerificationEvidenceStore.forScope(taskId, chatId, snapshotId)` zwraca wyłącznie rekordy o dokładnie zgodnym zakresie; brak któregokolwiek identyfikatora jest błędem `SCOPE_ID_EMPTY`.
- Ledger zapisuje format V2 z 10 polami. Parser nadal odczytuje V1; rekordy V1 pozostają jawnie niepowiązane z czatem/snapshotem (puste pola), a pierwszy zapis migruje całą zawartość do V2 bez usuwania starszych rekordów.
- `ChangeSetStore` przechowuje `chatId`; nowe zestawy patchy zapisują bieżący czat.
- `ProjectTestRunner` otrzymuje jawne `taskId` i `chatId`, a wszystkie cztery wyniki kontroli zawierają ten sam `taskId/chatId/snapshotId`. Wcześniej runner błędnie używał snapshotId jako taskId.
- Weryfikacja patcha przekazuje identyfikatory z wybranego ChangeSet; raport wskazuje task/chat/snapshot.

## Walidacja wykonana

- `VerificationEvidence` + `VerificationEvidenceStore` + testy: `kotlinc -Werror`, lokalny shim runner **17/17 PASS**.
- `ProjectTestRunner` wraz z workspace/patch/manifest/scanner i testami: `kotlinc -Werror`, lokalny shim runner **3/3 PASS**.
- `ChangeSetStore` + `ChangeSafetyGuard`: kompilacja Kotlin z atrapami Android Context/SharedPreferences i JSON (shim); bez `-Werror` dla samych atrap z ostrzeżeniami o nieużywanych parametrach.
- Łącznie uruchomione testy jednostkowe w lokalnych shim runnerach: **20/20 PASS**.
- ZIP source archive: test integralności `unzip -t` po utworzeniu.

## NOT RUN / ograniczenia

- Pełny Gradle/JUnit/Robolectric/`assembleDebug`/APK/urządzenie: NOT RUN.
- `MainActivity` nie został zbudowany przez Android SDK; call sites sprawdzono statycznie.
- Testy ChangeSetStore na rzeczywistym Android SharedPreferences/JSON: NOT RUN.
- Rejestr wywołań narzędzi z host-generated `executionId` nie jest jeszcze podłączony. Istniejące `toolExecution()` nadal przyjmuje executionId jako parametr; nie wolno używać go do potwierdzania rzeczywistego uruchomienia, dopóki nie powstanie zaufany host adapter.
- MASTER nie konsumuje jeszcze automatycznie ledgeru; 0.6.90 przygotowuje bezpieczne wiązanie zakresu, ale nie wybiera samodzielnie dowodów do syntezy.
- Legacy V1 evidence i stare ChangeSet bez chatId są zachowane, ale pozostają niepowiązane i nie są zwracane przez exact-scope lookup.
- Companion transport pozostaje BLOCKED; Companion pkt 5 nadal wymaga Gradle/Robolectric/Android Keystore/device.

## Następny etap

1. Dodać host-owned `ToolExecutionRegistry`: aplikacja generuje executionId, tworzy pending invocation i może zamknąć wyłącznie uchwyt wydany przez registry.
2. Podłączyć rzeczywiste adaptery narzędzi do begin/complete; brak rzeczywistego wywołania nie może utworzyć TOOL_EXECUTION PASS.
3. Dodać selektor/rezolwer zakresu dowodów dla MASTER-a: jawny task/chat/snapshot; żadnego „ostatniego pasującego” dowodu z innego zadania.
4. Przekazywać do `MasterResponseCheck` ocenę wyłącznie z exact-scope ledgeru.
