# AI MASTER PRO 0.6.21 — zweryfikowany bundle executora

## Zakres

- Eksport wyłącznie ze snapshotu, który przechodzi `ProjectWorkspaceStore.verifySnapshot()`.
- Nowy, stabilny binarny format `manifest.bin` (magic `AIME`, wersja formatu 1) z dekoderem fail-closed.
- Manifest zawiera identyfikatory bundle/workspace/snapshot, czas, uporządkowaną listę plików, rozmiary i SHA-256.
- Archiwum ZIP zawiera wyłącznie `manifest.bin` oraz `project/<relative-path>`.
- Każdy plik jest ponownie sprawdzany przed kopiowaniem i haszowany ponownie podczas weryfikacji gotowego ZIP.
- Weryfikacja wymaga dokładnego zestawu wpisów, bez duplikatów, traversal i nieznanych elementów.
- Eksport do lokalizacji poza prywatnym workspace, bez nadpisywania istniejącego pliku; zapis przez plik tymczasowy i atomowe przeniesienie.
- Eksport nie uruchamia żadnego kodu projektu i nie zmienia aktywnego workspace.

## Granice

- To jest API/core eksportu; nie dodano jeszcze UI wyboru lokalizacji ani transportu do PC.
- Nie istnieje jeszcze companion desktopowy ani OS-level sandbox.
- Receipt nadal nie jest podłączony do `ChangeSetStore`; `PROJECT_TESTS` pozostaje `NOT_RUN`.
- Pełny Gradle/APK wymaga środowiska Android SDK i dystrybucji Gradle.

## Dodatkowe poprawki audytowe

- Odrzucono symlink jako ścieżkę docelową i wymagane jest bezpieczne utworzenie unikalnego pliku tymczasowego.
- Walidator receipt odrzuca wersje executora zawierające znaki sterujące/nowe linie oraz raporty, których czas wykonania przekracza zadeklarowany timeout.
- Testy regresyjne potwierdzają te przypadki.
