# 0.6.82-SOURCE — trwałość wpisu katalogowego markera

- Baza: 0.6.81-SOURCE.
- Marker blokady jest publikowany przez unikalny plik tymczasowy, fsync jego zawartości i atomowy rename; od 0.6.82 po rename wykonywany jest również fsync katalogu nadrzędnego.
- Usunięcie markera synchronizuje katalog przed zwolnieniem process-local latch.
- Test Robolectric rozszerzono o ścieżkę publikacji/usuwania. Test dodany, ale nieuruchomiony.
- Host JDK 21/Linux NIO: 100/100 cykli fsync pliku → atomic move → fsync katalogu → delete → fsync katalogu PASS. Nie jest to test Android/OEM.
- Gradle/Robolectric/Android Keystore/APK/device NOT RUN.
- Transport nadal BLOCKED.

- Dodano powtarzalny host harness w `tools/companion_directory_fsync_harness.kt`; nie zależy od Android SDK ani Gradle.
