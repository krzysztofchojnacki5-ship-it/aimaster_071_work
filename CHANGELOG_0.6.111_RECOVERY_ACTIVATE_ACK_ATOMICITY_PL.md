# CHANGELOG — AI MASTER PRO 0.6.111-SOURCE

## Cel
Zamknąć okno TOCTOU pomiędzy aktywacją `HostActiveChangeSetStore` a usunięciem trwałego patch receipt. W 0.6.110 obie operacje były poprawnie chronione blokadą, ale odbywały się w dwóch osobnych sekcjach krytycznych; import mógł wejść pomiędzy nimi.

## Zmiany produkcyjne
- Dodano `PatchReceiptRecoveryCommitGate`, który wykonuje końcową walidację exact chat/workspace, aktywację zakresu, ponowną kontrolę czatu i acknowledge w jednej sekcji `ProjectWorkspaceOperationLock`.
- `MainActivity.recoverPendingPatchReceiptAtStartup()` przekazuje aktywację i acknowledge do tego gate'a. Nie wywołuje już osobno synchronizowanej metody aktywacji, co zachowuje naprawiony w 0.6.110 lock order.
- Import workspace nie może zmienić manifestu pomiędzy ustawieniem aktywnego pointera a acknowledge receipt.
- Jeżeli czat zmieni się w trakcie aktywacji, gate unieważnia właśnie aktywowany ChangeSet przed acknowledge.
- Jeżeli aktywacja nie powiedzie się, a czat/workspace nadal są dokładnie te same, receipt pozostaje do retry. Niepowodzenie acknowledge jest osobnym wynikiem, bez fałszywego sukcesu.

## Testy
- Nowy `patch_receipt_commit_gate_harness`: 5/5 PASS — import nie przeplata się z aktywacją/ack, chat switch podczas aktywacji powoduje invalidation, retry po rzeczywistym błędzie aktywacji, stale workspace nie aktywuje starego zakresu, acknowledge failure jest jawny.
- `patch_recovery_concurrency_harness`: 3/3 PASS; MainActivity lock-order static guard PASS.
- `patch_receipt_coordinator_harness`: 9/9 PASS.
- `patch_receipt_protocol_harness`: 8/8 PASS.
- `patch_receipt_policy_harness`: 11/11 PASS.
- `workspace_operation_lock_harness`: 5/5 PASS.
- Łącznie w tej iteracji: 41 jawnych przypadków PASS + statyczny guard.

## Testy niepotwierdzone
- `patch_receipt_filesystem_integration_harness`: ponowne uruchomienie zakończyło się timeoutem podczas kompilacji; brak nowego wyniku w 0.6.111. Ostatni wynik 7/7 pochodzi z 0.6.110.
- `verification_resolver_harness`: ponowne uruchomienie zakończyło się timeoutem; brak nowego wyniku w 0.6.111. Ostatni wynik 73/73 pochodzi z wcześniejszej paczki.
- ChangeSet compile shim: NOT RUN w tej iteracji.
- Gradle/JUnit/Robolectric/Android compile/APK/device/process-death: NOT RUN.

## Ograniczenia i stan
- Gate i blokada są testowane hostowym JVM; nie dowodzi to jeszcze atomowości Android SharedPreferences ani lifecycle.
- `versionName=0.6.54`, `versionCode=80`; bez APK.
- Companion transport pozostaje BLOCKED.
