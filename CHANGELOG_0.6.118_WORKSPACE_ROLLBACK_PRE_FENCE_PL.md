# CHANGELOG 0.6.118-SOURCE — workspace rollback pre-fence

## Zmiana
- `PatchReceiptRecoveryCoordinator.reconcile()` sprawdza bieżącą workspace identity przed jakimkolwiek backend I/O związanym z odzyskiwaniem.
- Dopuszczone są tylko `beforeWorkspaceIdentity` i `workspaceIdentity` zapisane w pierwotnym receipt.
- Po `recoverRequestedRollback()` koordynator ponownie sprawdza identity, ponieważ poprawny rollback może zmienić workspace z target na before.
- Obca identity blokuje rollback, snapshot verification, ledger read i wszystkie mutacje.

## Testy
- Dodano regresję: receipt z `rollbackRequested=true` i obcym workspace nie może wywołać `recoverRequestedRollback()`.
- Rozszerzono istniejącą regresję obcego workspace o brak snapshot verification i brak ledger I/O.
- Recovery coordinator: 19/19 PASS.
- Łącznie 136 hostowych przypadków PASS w zestawach regresyjnych; MainActivity lock-order guard PASS.

## Ograniczenia
- Gradle/Robolectric/Android SharedPreferences/process recreation/Android build/APK/device NOT RUN.
- Companion transport pozostaje BLOCKED.
