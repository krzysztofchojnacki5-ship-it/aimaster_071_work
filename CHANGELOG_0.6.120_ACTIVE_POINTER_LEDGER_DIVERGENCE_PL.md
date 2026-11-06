# CHANGELOG 0.6.120 — Active pointer / ledger divergence

## Zmiana
`HostActiveChangeSetStore.readActive()` usuwa teraz aktywny wskaźnik, jeśli poprawny ledger jednoznacznie dowodzi, że wskazany ChangeSet nie istnieje lub nie zgadza się z zapisanym chatId/taskId/snapshotId/status.

## Dlaczego
Wcześniej odczyt zwracał `INVALID`, ale pozostawiał stale pointer. Gdyby późniejszy restore lub retry przywrócił zgodność ledgeru z tym wskaźnikiem, stary zakres mógłby ponownie stać się aktywny bez nowej jawnej selekcji.

## Ważne rozróżnienie
- Ledger `CORRUPT` / wyjątek odczytu: nie usuwamy wskaźnika; chwilowa niedostępność nie dowodzi, że jest nieaktualny.
- Ledger `VALID`, ale rekord brakujący/niejednoznaczny/niespójny: próbujemy usunąć wskaźnik.
- Nieudane usunięcie: nadal zwracamy `INVALID` i ujawniamy, że trwała invalidacja się nie powiodła.

## Testy
Dodano 3 testy do `ChangeSetStoreTest` (Robolectric/JUnit ADDED / NOT RUN) oraz 3 przypadki do hostowego fault harnessu. Cały fault harness: 13/13 PASS.
