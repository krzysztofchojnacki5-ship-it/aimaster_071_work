# AI MASTER PRO — CHANGELOG 0.6.119-SOURCE

## Cel
Domknięcie wyścigów pomiędzy startup receipt recovery, importem/restore workspace oraz końcową aktywacją ChangeSet.

## Zmiany produkcyjne
- `PatchReceiptRecoveryCommitGate` traktuje brak zgodności bieżącego czatu/workspace z oczekiwanym zakresem jako jawny przypadek stale scope.
- Przy stale scope nie aktywuje ChangeSet; najpierw wywołuje `invalidateActiveScope`, a dopiero po skutecznej invalidacji może potwierdzić receipt.
- Jeżeli invalidacja stale scope zawiedzie, zwraca `INVALIDATION_FAILED` i nie potwierdza receipt, zachowując możliwość retry.
- Dotychczasowe zachowanie pozostaje: błąd aktywacji przy niezmienionym scope zwraca `ACTIVATION_FAILED`; błąd ACK zwraca `ACKNOWLEDGE_FAILED`.

## Testy dodane/rozszerzone
- Commit gate: stale workspace nie może aktywować starego ChangeSet; wymagana invalidacja przed ACK.
- Commit gate: błąd invalidacji stale scope blokuje ACK.
- Filesystem integration: odtworzenie nowej instancji `ProjectWorkspaceStore` po symulowanym przerwaniu pomiędzy restore snapshotu a trwałym oznaczeniem receipt jako rolled back.
- Filesystem integration: restore workspace czeka na wspólną blokadę recovery; po restore commit gate unieważnia stary active scope i nie aktywuje ChangeSet.
- Harness filesystem używa produkcyjnego `ProjectWorkspaceStore`, `PatchReceiptRecoveryCoordinator`, `PatchReceiptRecoveryCommitGate` oraz rzeczywistego tymczasowego filesystemu. Ledger i active pointer pozostają kontrolowanymi adapterami host JVM.

## Wyniki hostowe
| Zestaw | Wynik |
|---|---:|
| Commit gate | 9/9 PASS |
| Recovery coordinator | 19/19 PASS |
| Recovery policy | 11/11 PASS |
| Receipt protocol | 8/8 PASS |
| Recovery concurrency | 3/3 PASS |
| Filesystem recovery integration | 9/9 PASS |
| ChangeSet storage fault matrix | 10/10 PASS |
| Workspace operation lock | 5/5 PASS |
| Verification resolver | 73/73 PASS |
| **Łącznie jawne przypadki hostowe** | **147 PASS** |
- MainActivity lock-order static guard: PASS.

## Ograniczenia / NOT RUN
- Pełny Gradle, Android compile, JUnit/Robolectric, rzeczywiste SharedPreferences, Android process death, APK i test na urządzeniu: NOT RUN.
- Test odtworzenia magazynu tworzy nową instancję `ProjectWorkspaceStore` nad tym samym katalogiem host filesystem; nie jest to dowód zachowania Android runtime.
- Nie deklarujemy naprawy środowiska Gradle/Android SDK.

## Niezmienione ograniczenia projektu
- `versionName=0.6.54`, `versionCode=80`; APK nie generowano.
- Companion Transport pozostaje BLOCKED.
