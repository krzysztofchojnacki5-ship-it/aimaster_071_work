# CHANGELOG — AI MASTER PRO 0.6.106-SOURCE

Baza: `AI_MASTER_PRO_0_6_105_HOST_LEDGER_STORAGE_SCOPE_SOURCE.zip`.

## Zmiany
- Dodano samodzielny JVM harness `tools/workspace_operation_lock_harness` dla produkcyjnego `ProjectWorkspaceOperationLock`.
- Testy obejmują reentrant nested calls, nieblokujący `tryWithLock()` podczas zajętej blokady, zwalnianie po contention, wzajemne wykluczanie 8 wątków (łącznie 1200 sekcji krytycznych), propagację wyjątków oraz cleanup po wyjątku w `tryWithLock()`.
- Nie zmieniono polityki recovery, formatu journal/receipt ani zachowania Companion.
- Przegląd call sites potwierdził, że hostowy import ZIP, apply i kompensacyjny rollback są osłonięte wspólną blokadą; aktywacja ponownie sprawdza workspace identity pod tą blokadą.

## Walidacja
- Workspace operation lock harness: **5/5 PASS**.
- Patch receipt recovery policy: **11/11 PASS**.
- Workspace transaction recovery: **12/12 PASS**.
- Verification resolver/lock: **73/73 PASS**.
- ChangeSet/HostActive compile shim (`kotlinc -Werror`): **PASS**.
- Gradle/JUnit/Robolectric/pełny Android compile/APK/device: **NOT RUN**.

## Zachowane ograniczenia
- `versionName=0.6.54`, `versionCode=80`.
- Companion transport pozostaje **BLOCKED**.
