# Changelog 0.6.131 — staged import journal filesystem prototype

- Dodano niezależny hostowy harness plikowego journalu: wersja formatu, ograniczony identyfikator, allowlista stanów, SHA-256 payloadu, limit rozmiaru, fail-closed parser.
- Zapis używa pliku tymczasowego, `FileChannel.force(true)` i `ATOMIC_MOVE`; brak atomowego rename powoduje odmowę zamiast nieatomowego fallbacku.
- Fault injection przed rename potwierdza zachowanie poprzedniego rekordu.
- Nowy harness: 8/8 PASS.
- Pełna regresja hostowa: 203 przypadki PASS; compile-only shim i source guards PASS.
- Prototyp nie jest używany przez produkcyjny `ProjectWorkspaceStore`; nie dodano konkurencyjnego journalu. Import ZIP nadal pod global lockiem.
- Android/Gradle/Robolectric/APK/device/process-death NOT RUN; Android preflight BLOCKED.
- App version `0.6.54 / code 80`; Companion Transport BLOCKED.
