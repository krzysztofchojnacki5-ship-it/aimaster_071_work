# AI MASTER PRO 0.6.22 — skan sekretów przed eksportem

## Zmiany

- Dodano lokalny `BundleContentScanner` z ograniczeniem 8 MB na plik i 40 MB łącznie.
- Skan obejmuje surowe bajty pliku, więc nie wymaga zaufania do rozszerzenia ani deklarowanego typu MIME.
- Wykrywane są m.in. klucze prywatne PEM, popularne tokeny dostawców, Bearer tokeny, klucze API oraz przypisania haseł/sekretów.
- Podejrzane nazwy plików (np. `*.secrets.*`, `credentials`, `api_token`) blokują eksport; zwykłe nazwy klas typu `TokenManager.kt` nie są automatycznie odrzucane.
- Eksport snapshotu uruchamia skan po ponownej kontroli SHA-256 i przed budową ZIP.
- Każde wykrycie blokuje eksport. Raport podaje wyłącznie kod, ścieżkę i ogólny komunikat — nigdy dopasowaną wartość sekretu.
- Dodano testy blokady sekretu w zwykłym pliku i redakcji wartości w komunikacie błędu.

## Granice

- Skan jest lokalną heurystyką, nie gwarantuje wykrycia każdego możliwego sekretu i może wymagać ręcznego przeglądu wyników.
- Nie dodano jeszcze UI wyboru plików ani transportu do PC.
- Nie istnieje jeszcze companion desktopowy ani OS-level sandbox; `PROJECT_TESTS` pozostaje `NOT_RUN`.
