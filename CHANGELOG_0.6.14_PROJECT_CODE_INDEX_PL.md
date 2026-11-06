# AI MASTER PRO 0.6.14 — indeks projektu i kontrolowany odczyt kodu

## Wdrożone
- Dodano deterministyczny indeks metadanych projektu: ścieżki, rozpoznane języki/formaty, moduły i rozmiary.
- Indeks pomija znane katalogi generowane i pliki binarne; nie ładuje treści plików.
- Dodano ograniczony wybór plików po dopasowaniu zapytania do ścieżek (bez deklarowania analizy semantycznej/symboli).
- Dodano kontrolowany podgląd plików tekstowych do 24 KB: plik musi należeć do manifestu, przejść kontrolę ścieżki, rozmiaru i SHA-256 oraz poprawne dekodowanie UTF-8.
- Podgląd blokuje wzorce przypominające klucze API, tokeny, hasła i sekrety.
- W Centrum dodano indeks struktury projektu oraz lokalny podgląd wybranego pliku.
- Zwiększono limit odczytu metadanych indeksu do pełnych 3000 wpisów workspace.
- Weryfikacja integralności wykrywa również dowiązania symboliczne.
- Import nowego ZIP jest blokowany, jeśli bieżący workspace odbiega od manifestu; chroni to przed utrwaleniem zmienionych/nieznanych plików w kopii zapasowej.

## Granice funkcji
- Indeks 0.6.14 analizuje metadane i ścieżki, nie AST, symbole ani zależności wywołań.
- Odczyt pliku jest lokalny i ręcznie inicjowany w Centrum. Treść nie jest automatycznie wysyłana do modeli.
- Agenci nadal nie mogą modyfikować workspace. Generator diffów, peer review diffów i zapis patchy są kolejnymi etapami.

## Dodatkowa naprawa wykryta podczas testów
- Test drugiego importu ujawnił konflikt: publiczna kontrola integralności uruchamiała procedurę sprzątania katalogów staging, co usuwało aktywny staging nowego importu. Rozdzielono weryfikację wewnętrzną od procedury odzyskiwania; drugi import ponownie tworzy snapshot i przełącza workspace prawidłowo.
- Dodano regresyjny test ponownego importu oraz test odrzucania dowiązań symbolicznych.
