# Audyt odzyskiwania osieroconych artefaktów swap — 0.6.133

## Zakres

Audyt rzeczywistego `ProjectWorkspaceStore.recoverInterruptedSwap()` i `cleanupInterruptedStaging()` po zmianach 0.6.132. Nie zmieniano formatu AISW v1/v2 ani kolejności commit point.

## Ustalenie

Po rozpoznaniu poprawnie zatwierdzonej pary `current/` + `current.manifest` recovery usuwa artefakty poprzedniej transakcji wskazane przez bieżący journal. Jednak pozostałe, starsze katalogi `.previous-*` i odpowiadające im manifesty mogły pozostać po wcześniejszej awarii sprzątania. Dotychczasowe sprzątanie stagingu nie obejmowało tych osieroconych backupów.

## Zmiana produkcyjna

`cleanupInterruptedStaging()` wykonuje teraz dodatkowy, fail-closed warunek:

1. Weryfikuje, że `current/` istnieje i jest katalogiem, `current.manifest` jest plikiem oraz manifest poprawnie opisuje całe drzewo.
2. Dopiero po pozytywnej weryfikacji usuwa osierocone `.previous-*` katalogi i pliki `.previous-*.manifest`.
3. Symlinki są odpinane jako same wpisy; cleanup nie przechodzi przez ich cele.
4. Gdy `current` jest nieobecny, niekompletny lub niezgodny z manifestem, poprzednie artefakty pozostają nietknięte.

To zamyka lukę sprzątania bez zmiany semantyki wyboru zatwierdzonej transakcji. Nie jest to nowy mechanizm wyboru backupu ani staged import.

## Testy

- Rozszerzono rzeczywisty hostowy harness `workspace_transaction_recovery_harness` do 13 scenariuszy. Nowy scenariusz tworzy zatwierdzony workspace, osierocony backup z innej transakcji oraz journal wskazujący zatwierdzoną bieżącą parę; recovery zachowuje `current` i usuwa osierocone artefakty.
- Harness kompiluje rzeczywisty `ProjectWorkspaceStore.kt` i wykonuje go na hostowym JVM: 13/13 PASS.
- Dodano analogiczny test JUnit do `ProjectWorkspaceStoreTest.kt`: ADDED / NOT RUN (brak Gradle/JUnit runtime).
- Source guard wymaga walidacji bieżącego drzewa przed cleanupem i bezpiecznego traktowania symlinków.
- Pełna regresja hostowa: 204 PASS; compile-only shim i wszystkie source guards PASS.

## Ograniczenia

Host JVM/Linux nie dowodzi zachowania Android filesystem, trwałości po utracie zasilania ani semantyki `fsync`/directory fsync na wszystkich urządzeniach. Gradle, JUnit, Robolectric, Android build, APK, emulator/device i rzeczywisty process-death na Androidzie: NOT RUN / środowisko BLOCKED.

Staged import pozostaje projektem/modelami i nie jest zintegrowany z produkcyjnym importerem. Import ZIP nadal odbywa się pod globalnym `ProjectWorkspaceOperationLock`. Companion Transport pozostaje BLOCKED. Wersja aplikacji: `0.6.54 / versionCode 80`.
