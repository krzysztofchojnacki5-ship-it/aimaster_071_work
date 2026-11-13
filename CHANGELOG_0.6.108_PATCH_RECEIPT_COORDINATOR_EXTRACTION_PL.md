# CHANGELOG — AI MASTER PRO 0.6.108-SOURCE

## Cel
Wydzielić rzeczywistą kolejność uzgadniania trwałego `workspace.patch.receipt` z `MainActivity` do koordynatora produkcyjnego, z którego korzystają zarówno aplikacja, jak i testy JVM.

## Zmiany
- Dodano `PatchReceiptRecoveryCoordinator` z typowanymi wejściami/wyjściami i backendem dla operacji workspace, ChangeSet ledger oraz active scope.
- Koordynator wykonuje tę samą produkcyjną sekwencję: dokończenie jawnie zażądanego rollbacku, walidacja snapshotu i jego bazowej identity, walidacja jednoznacznego ChangeSet/chat/snapshot, decyzja recovery, zapis snapshotu, zmiana statusu i unieważnienie active scope.
- `MainActivity.recoverPendingPatchReceiptAtStartup()` używa teraz koordynatora pod `ProjectWorkspaceOperationLock`; aktywacja pointera nadal następuje poza tą sekcją dopiero po ponownym sprawdzeniu chatId i workspace identity, a receipt jest potwierdzany dopiero po uzgodnieniu.
- Dodano JVM harness testujący bezpośrednio produkcyjny koordynator, nie kopię jego algorytmu. Testuje idempotencję, błędy zapisu snapshotu/statusu, uszkodzony snapshot, niezgodność czatu, rollback oraz błąd invalidation.

## Wyniki
- `patch_receipt_coordinator_harness`: 9/9 PASS (`kotlinc -Werror`).
- `patch_receipt_policy_harness`: 11/11 PASS.
- `patch_receipt_protocol_harness`: 8/8 PASS.
- `workspace_transaction_recovery_harness`: 12/12 PASS.
- `workspace_operation_lock_harness`: 5/5 PASS.
- `verification_resolver_harness`: 73/73 PASS.
- `changeset_store_compile_harness`: PASS.

## Ograniczenia
- Pełny Android compile, Gradle, JUnit/Robolectric, APK i test urządzenia: NOT RUN.
- Nowy harness uruchamia rzeczywisty `PatchReceiptRecoveryCoordinator`, ale backend jest fake; nie stanowi dowodu wykonania Android SharedPreferences/process recreation.
- `MainActivity` integracja wymaga pełnego Android compile. Sam test koordynatora nie potwierdza poprawności wszystkich zależności Android.
- `versionName=0.6.54`, `versionCode=80`; APK nie został utworzony.
- Companion transport pozostaje BLOCKED.
