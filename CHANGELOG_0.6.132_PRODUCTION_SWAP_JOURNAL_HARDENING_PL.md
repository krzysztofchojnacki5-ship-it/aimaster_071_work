# CHANGELOG — AI MASTER PRO 0.6.132

- Utwardzono rzeczywisty `ProjectWorkspaceStore.readSwapJournal()` limitem 16 KiB sprawdzanym przed otwarciem/parsingiem.
- Reader wymaga zwykłego pliku, odrzuca symlink i pilnuje bezpośredniego położenia w prywatnym katalogu root.
- Zachowano format binarny AISW v1/v2, walidację identyfikatora/hash/metadanych patcha oraz odrzucanie trailing bytes.
- Dodano trzy testy JUnit: oversized journal, trailing bytes, legacy v1 recovery (ADDED / NOT RUN).
- Dodano `tools/workspace_swap_journal_guard/SourceGuard.py`.
- Produkcyjny import ZIP nadal działa pod globalnym workspace lock. Nie wdrożono staged importer API.
- Regresja istniejących harnessów: 203/203 host PASS; compile-only shim PASS; source guards PASS. Nowe testy JUnit nie są wliczone.
- Android/Gradle/JUnit/Robolectric/APK/device/process-death NOT RUN; środowisko nadal BLOCKED.
