# BUILD STATUS — AI MASTER PRO 0.6.136-SOURCE

## Baza i wersje
- Baza wejściowa: `AI_MASTER_PRO_0_6_135_TEMP_WRITER_OWNERSHIP_GUARD_SOURCE.zip`.
- SHA-256 bazy: `6eb632a71b63369c4b81b551a783f5569d517dfa1c2a535d147216a7029a6501`; `unzip -t` PASS przed pracą.
- App version bez zmian: `0.6.54`, `versionCode=80`.
- Companion Transport: **BLOCKED**.

## Zmiana 0.6.136 — fault-injectable durable publisher
- `writeSwapJournal()` i `writePatchReceipt()` delegują teraz zapis do wspólnego `publishWorkspaceDurableFile()`.
- Wspólny publisher kontroluje pełny cykl: `createNewFile()` i przejęcie ownership, serializację payloadu, buffered `flush()`, `FileDescriptor.sync()`, atomowe przeniesienie i best-effort directory sync; cleanup usuwa wyłącznie temp utworzony przez ten przebieg.
- Wprowadzono internal parametry operacji `flushAndSync`, `moveAtomically` i `syncDirectory`. To lokalny seam testowy przekazywany per wywołanie, bez global mutable hooks i bez zmiany publicznego API `ProjectWorkspaceStore`.
- Produkcyjna ścieżka nadal wymaga `ATOMIC_MOVE`; nie ma fallbacku na zwykłe `Files.move`. `AtomicMoveNotSupportedException` nadal jest opakowywany w dotychczasowy komunikat writerów.
- Directory fsync nadal best-effort: jego błąd po publikacji nie cofa zatwierdzonego pliku i nie zamienia sukcesu w failure.
- Patch receipt nadal usuwa transaction-specific stale temp przed nowym zapisem dopiero w ścieżce recovery potwierdzonej journalem. Format AISW/receipt, commit point, lock scope i semantyka recovery bez zmian.

## Fault matrix — rzeczywisty helper produkcyjny
`workspace_transaction_recovery_harness` kompiluje i wykonuje rzeczywisty `ProjectWorkspaceStore.kt`; wynik **28/28 PASS** (wcześniej 19/19, dodano 9 scenariuszy):
1. kolizja CREATE_NEW zachowuje obcy/pre-existing temp;
2. awaria serializacji payloadu nie publikuje pliku i sprząta własny temp;
3. wstrzyknięta awaria flush nie publikuje pliku;
4. wstrzyknięta awaria `fd.sync()` nie publikuje pliku;
5. błąd atomowego move zachowuje poprzedni target;
6. `AtomicMoveNotSupportedException` nie uruchamia nieatomowego fallbacku;
7. błąd directory fsync po publikacji jest best-effort;
8. udana atomowa podmiana publikuje kompletny payload;
9. directory sync następuje dopiero po opublikowaniu targetu.

To testy hostowe z kontrolowanymi błędami na seamie produkcyjnego helpera, nie symulacja awarii zasilania Androida ani dowód trwałości konkretnego urządzenia.

## Testy hostowe
| Suite | Wynik |
|---|---:|
| ChangeSet storage fault matrix | 21/21 PASS |
| ChangeSet + HostActive compile shim (`-Werror`) | PASS (compile only) |
| Patch receipt commit gate | 9/9 PASS |
| Patch receipt recovery coordinator | 19/19 PASS |
| Patch receipt recovery policy | 11/11 PASS |
| Patch receipt protocol | 8/8 PASS |
| Recovery concurrency | 3/3 PASS |
| Workspace operation lock | 10/10 PASS |
| Workspace transaction recovery + durable publisher | 28/28 PASS |
| Verification resolver | 73/73 PASS |
| Filesystem recovery integration | 9/9 PASS |
| Staged import contract/lifecycle model | 20/20 PASS |
| Staged import journal filesystem prototype | 8/8 PASS |
| **Łącznie runtime host** | **219 PASS** |

Wszystkie 12 runtime harnessów uruchomiono i zaliczyły. Source guards PASS. Compile shim i source guards nie są wliczone do 219.

## Testy dodane, ale niewykonane
- `ProjectWorkspaceStoreTest`: 2 wcześniejsze ownership testy oraz 2 nowe testy publishera (atomic move failure i best-effort directory sync) — **ADDED / NOT RUN**.
- Brak Gradle/JUnit/Robolectric w tym środowisku; nie oznaczać tych testów jako PASS.

## Android / ograniczenia środowiska
- Android preflight: **BLOCKED**. Java 21 dostępna; brak `gradle/wrapper/gradle-wrapper.jar`, systemowego Gradle i Android SDK/`ANDROID_HOME`; DNS `services.gradle.org` niedostępny.
- Gradle, Android compile, JUnit/Robolectric, APK, emulator/device, Android filesystem, SharedPreferences, process-death i power-loss tests: **NOT RUN**.
- Nie zbudowano APK.

## Niezmienione granice bezpieczeństwa
- Public API store, AISW v1/v2, patch receipt format, commit point i global workspace lock pozostają bez zmian.
- Brak nieatomowego fallbacku; directory fsync nadal best-effort.
- Staged import nadal nie jest wdrożony produkcyjnie; ZIP import pozostaje pod global lock.
- Companion Transport pozostaje BLOCKED.
