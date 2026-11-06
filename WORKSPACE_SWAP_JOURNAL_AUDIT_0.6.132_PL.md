# Audyt produkcyjnego swap journalu — 0.6.132

## Zakres

Audyt dotyczy istniejącego `ProjectWorkspaceStore` i jego `workspace.swap.journal`, nie niezależnego prototypu z 0.6.131. Nie wprowadzono drugiego journalu ani zmiany formatu produkcyjnego.

## Ustalenia

1. Produkcyjny journal ma binarny nagłówek `AISW`, obsługuje wersję 1 (bez metadanych patcha) i wersję 2 (z opcjonalnymi metadanymi patcha).
2. Zapis używa unikalnego pliku tymczasowego `.workspace.swap.<UUID>.tmp`, `FileDescriptor.sync()`, a następnie `ATOMIC_MOVE + REPLACE_EXISTING`. Brak obsługi atomowego rename kończy się błędem; nie ma nieatomowego fallbacku.
3. Recovery akceptuje nowy current wyłącznie po zgodności SHA-256 manifestu i weryfikacji całego drzewa; w przeciwnym razie próbuje odtworzyć poprzednią parę albo usuwa niekompletny pierwszy import.
4. Patch receipt jest odtwarzany z journalu przy recovery i porównywany z istniejącym receipt. Sprzeczne receipt blokuje recovery.
5. Directory fsync jest best-effort. Nie wolno deklarować gwarancji power-loss durability na Androidzie na podstawie samego hostowego testu.

## Zmiana 0.6.132

- Dodano limit `MAX_SWAP_JOURNAL_BYTES = 16 KiB`.
- `readSwapJournal()` odrzuca plik przed parsowaniem, jeżeli nie jest zwykłym plikiem, jest symlinkiem, wychodzi poza prywatny katalog albo ma rozmiar poza `1..16 KiB`.
- Zachowano kompatybilność wersji 1 i 2 oraz dotychczasową walidację pól i EOF (nadmiarowe bajty odrzucane).
- Dodano trzy testy JUnit do `ProjectWorkspaceStoreTest`: oversized journal, trailing bytes i legacy v1 recovery. Testy dodane, ale nieuruchomione, ponieważ brak Gradle/JUnit runtime w środowisku.
- Dodano source guard wiążący ograniczenie rozmiaru i ścieżki z produkcyjnym readerem oraz sprawdzający obecność testów regresji.

## Granice i ryzyka pozostające

- Nie dodano checksum do binarnego swap journalu; integralność docelowego manifestu i drzewa jest weryfikowana oddzielnie. Ewentualna zmiana formatu wymaga jawnej wersji 3 i migracji.
- Directory fsync pozostaje best-effort; sekwencje rename katalogu current/manifest nie mają potwierdzonej gwarancji power-loss na docelowym Android filesystem.
- Testy JUnit dla `ProjectWorkspaceStore` są ADDED / NOT RUN. Hostowe 203 przypadki regresji nie obejmują tych trzech nowych testów produkcyjnego store.
- Nie podłączono staged importer API. `importZip()` nadal wykonuje ekstrakcję i swap pod globalnym workspace lock.

## Wniosek

0.6.132 ogranicza powierzchnię ataku i ryzyko nadmiernego wejścia parsera istniejącego journalu, nie zmieniając jego protokołu binarnego. Nie jest to jeszcze dowód crash consistency ani Android durability.
