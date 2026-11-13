# CHANGELOG — 0.6.135: ownership-scoped temporary writer cleanup

- Audyt `writeSwapJournal()` i `writePatchReceipt()` wykazał, że bezwarunkowe `temp.delete()` w `finally` mogło usunąć istniejący wpis, którego bieżący writer nie utworzył, np. po kolizji `createNewFile()`.
- Oba writery mają teraz `ownsTemp=false`; ustawiają `true` wyłącznie po udanym `createNewFile()`. Cleanup w `finally` działa tylko wtedy, gdy writer rzeczywiście uzyskał własność ścieżki.
- Dodano `cleanupOwnedTemporaryFile()`, wykorzystujący `Files.deleteIfExists()` do unlink wpisu bez podążania za symlinkiem. Cleanup pozostaje best-effort i nie maskuje błędu zapisu/rename.
- Host harness wykonujący rzeczywisty `ProjectWorkspaceStore.kt` rozszerzono z 15 do 19 przypadków. Nowe przypadki sprawdzają zachowanie obcego pliku, usuwanie własnego pliku oraz semantykę symlinków.
- Dodano dwa testy JUnit (ADDED / NOT RUN) i source guard dla ownership w obu writerach.
- Pełna regresja: 210 hostowych PASS; compile shim i source guards PASS.
- Nie zmieniono commit point, formatu AISW/receipt, atomowego rename ani directory fsync policy. Nie wdrożono staged importu.
- Android/Gradle/JUnit/Robolectric/APK/device/process-death NOT RUN: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. App `0.6.54 / code 80`; Companion Transport BLOCKED.
