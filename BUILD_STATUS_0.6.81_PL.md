# Build status — 0.6.81-SOURCE

- Parent: `AI_MASTER_PRO_0_6_80_COMPANION_DURABLE_TRUST_LOCK_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: usunąć kolizję stałej nazwy pliku tymczasowego podczas równoległego publikowania trwałego markera Companion.

## Zmiany

- `writePersistentTrustLockFile()` tworzy unikalny plik tymczasowy przez `Files.createTempFile()` w tym samym katalogu co docelowy marker.
- Finalny plik jest nadal publikowany atomowo przez `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`.
- Cleanup pliku tymczasowego nie maskuje pierwotnej awarii; wtórny błąd trafia do `suppressed`.
- Dodano test Robolectric: dwie instancje store, 32 równoległe publikacje, weryfikacja treści finalnego markera i braku plików `.tmp`.

## Walidacja tej paczki

- Statyczna kontrola patcha i zgodności dokumentacji: PASS.
- Test Robolectric współbieżnych zapisów: ADDED / NOT RUN.
- Gradle/JUnit/Robolectric: NOT RUN.
- `kotlinc -Werror` dla pełnego Android store: NOT RUN w tym etapie.
- Android Keystore, restart procesu na urządzeniu, `assembleDebug`, APK i test urządzeniowy: NOT RUN.
- ZIP integrity i SHA-256: sprawdzone po utworzeniu archiwum.

## Ograniczenia

- Unikalne pliki tymczasowe eliminują kolizję nazw między równoległymi writerami. Nie zastępują testów zachowania konkretnego systemu plików Android przy utracie zasilania.
- Gwarancja restartowa 0.6.80 nadal ma tę samą granicę: równoczesna awaria zapisu obu markerów i usunięcia aliasu Keystore nie daje gwarancji trwałego fail-closed.
- Transport Companion pozostaje nieuruchomiony.
