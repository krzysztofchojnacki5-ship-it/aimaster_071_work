# Build status — 0.6.82-SOURCE

- Parent: `AI_MASTER_PRO_0_6_81_COMPANION_LOCK_MARKER_CONCURRENCY_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: domknąć trwałość atomowego markera blokady na poziomie metadanych katalogu.

## Zmiany

- Po zapisaniu i `fd.sync()` unikalnego pliku tymczasowego oraz atomowym `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` wykonywany jest `FileChannel.force(true)` na katalogu nadrzędnym. Sam fsync zawartości pliku nie gwarantuje utrwalenia wpisu katalogowego po rename.
- Usunięcie markera blokady synchronizuje katalog przed wyzerowaniem process-local latch. Jeśli synchronizacja katalogu zawiedzie, operacja nie odblokowuje bieżącego procesu; ścieżka błędu ponownie podejmuje fail-closed lock.
- Test Robolectric rozszerzono o jawne sprawdzenie ścieżki synchronizacji katalogu przy publikacji i usuwaniu markera.

## Walidacja

- Powtarzalny harness znajduje się w `tools/companion_directory_fsync_harness.kt`; uruchomienie: `kotlinc -Werror tools/companion_directory_fsync_harness.kt -include-runtime -d /tmp/companion-directory-fsync.jar && java -jar /tmp/companion-directory-fsync.jar`.
- Host JDK 21: `FileChannel.open(directory, READ).force(true)` PASS na katalogu roboczym.
- Niezależny host harness NIO: publikacja (fsync pliku → atomic move → fsync katalogu), usunięcie i fsync katalogu: 100/100 cykli PASS.
- Statyczna kontrola patcha/importów/testu: PASS.
- Robolectric test ADDED / NOT RUN; Gradle/JUnit/Robolectric NOT RUN.
- Android API 26+ i zachowanie konkretnego filesystemu urządzenia przy utracie zasilania: NOT RUN.
- `assembleDebug`, APK i test urządzeniowy: NOT RUN.

## Ograniczenia

- Test hosta potwierdza obsługę API NIO przez hostowy JDK/Linux, nie stanowi dowodu zachowania każdego OEM-owego Android filesystemu.
- Nadal obowiązuje granica 0.6.80: jednoczesna awaria obu markerów trwałych i usunięcia aliasu Keystore nie daje gwarancji fail-closed po restarcie.
- Transport Companion pozostaje nieuruchomiony.
