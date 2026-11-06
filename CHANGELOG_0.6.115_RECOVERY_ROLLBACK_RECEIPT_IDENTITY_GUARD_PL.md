# CHANGELOG — 0.6.115-SOURCE

Baza: 0.6.114-SOURCE.

## Zmiany
- Utwardzono `PatchReceiptRecoveryCoordinator` po wywołaniu `recoverRequestedRollback()`.
- Wynik backendu musi zachować dokładną tożsamość pierwotnego receipt: transactionId, changeSetId, chatId, snapshotId oraz obie workspace identity.
- Backend musi potwierdzić zakończony rollback (`rolledBack=true`, `rollbackRequested=false`). W przeciwnym razie recovery zatrzymuje się przed snapshot verification, ledger read i mutacjami.
- Dodano testy regresyjne dla nieukończonego rollbacku i próby podmiany transactionId.

## Testy
- Coordinator 15/15 PASS.
- Store fault matrix 10/10 PASS.
- Policy 11/11 PASS.
- Protocol 8/8 PASS.
- Concurrency 3/3 PASS + lock-order guard PASS.
- Łącznie 47 hostowych przypadków PASS oraz 1 statyczny guard PASS.

## Granice dowodów
Commit gate harness timeout w tej iteracji. Robolectric, Gradle, Android build, APK, urządzenie i process-death NOT RUN. Brak skonfigurowanego Android SDK i brak `gradle-wrapper.jar` w dostarczonej bazie. Companion transport BLOCKED.
