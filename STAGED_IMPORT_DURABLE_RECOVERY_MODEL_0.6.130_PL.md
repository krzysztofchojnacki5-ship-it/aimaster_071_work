# Staged Import — model trwałego journalu i recovery 0.6.130

## Status i granice

**HOST CONTRACT MODEL ONLY.** To rozszerzenie specyfikacji 0.6.129, nie implementacja produkcyjnego journalu ani test prawdziwego filesystemu Android. `ProjectWorkspaceStore.importZip()` pozostaje bez zmian i nadal wykonuje import pod globalnym `ProjectWorkspaceOperationLock`.

## Maszyna stanów modelu

| Stan | Znaczenie | Dozwolone przejścia |
|---|---|---|
| `PREPARED` | staging przygotowany, bez zmian `current/` | `COMMITTING`, `ABORTED` |
| `COMMITTING` | rozpoczęto commit; wynik swapu może być nieznany | `ABORTED` dla `NONE/OLD_CURRENT`; `SWAPPED_AWAITING_EVIDENCE` dla `NEW_CURRENT` |
| `SWAPPED_AWAITING_EVIDENCE` | nowy `current/` jest już widoczny, ale stary evidence pointer wymaga unieważnienia | `COMMITTED` po idempotentnej invalidacji |
| `COMMITTED` | swap i invalidacja evidence zakończone | stan terminalny |
| `ABORTED` | przygotowanie/commit zakończone bez przyjęcia importu | stan terminalny |

## Reguły recovery

1. `PREPARED` znaleziony po restarcie jest orphanem i może zostać usunięty bez dotykania `current/`.
2. `COMMITTING + NONE` oznacza brak potwierdzonego swapu; recovery zamyka próbę jako `ABORTED`.
3. `COMMITTING + OLD_CURRENT` oznacza, że stary workspace pozostał bieżący; próba jest zamykana jako `ABORTED`.
4. `COMMITTING + NEW_CURRENT` oznacza, że swap zaszedł. Recovery nie może cofnąć go tylko dlatego, że proces umarł przed aktualizacją stanu; przechodzi do `SWAPPED_AWAITING_EVIDENCE`.
5. `SWAPPED_AWAITING_EVIDENCE` wymaga unieważnienia starego active evidence pointer, a dopiero potem oznaczenia `COMMITTED`.
6. Powtórne recovery stanów terminalnych jest no-op. Invalidacja evidence i cleanup są idempotentne.
7. W razie niejednoznacznego/uszkodzonego journalu produkcyjny mechanizm musi pozostać fail-closed; model nie definiuje jeszcze formatu ani atomowości zapisu journalu.

## Testy dodane w 0.6.130

Rozszerzono `tools/staged_import_contract_harness/Runner.kt` o osiem scenariuszy:
- orphan `PREPARED` po restarcie;
- `COMMITTING` bez swapu;
- `COMMITTING` z wynikiem `OLD_CURRENT`;
- `NEW_CURRENT` rozpoznany po restarcie;
- invalidacja evidence przed `COMMITTED`;
- idempotencja recovery po commit;
- idempotencja invalidacji evidence;
- terminalność `COMMITTED` i `ABORTED`.

Wynik harnessu: **20/20 PASS** (12 testów kontraktu + 8 testów lifecycle). To dowód spójności modelu stanów, nie dowód trwałości na dysku.

## Niezamknięte wymagania przed produkcyjnym wdrożeniem

- format i wersjonowanie journalu, checksum oraz bezpieczne parsowanie;
- atomowy zapis/rename journalu i fsync tam, gdzie platforma to wspiera;
- rzeczywiste fault injection na granicach plików, katalogów i rename;
- startup recovery na Androidzie po force-stop/process death;
- synchronizacja cleanupu z aktywnymi lease oraz odporność na zegar systemowy cofnięty/zmieniony;
- testy dwóch procesów/instancji store, jeśli aplikacja kiedykolwiek dopuści taki model;
- pełna integracja z istniejącym swap journal, snapshotami i invalidacją `HostActiveChangeSetStore` bez drugiego konkurencyjnego journalu.

Do czasu spełnienia tych wymagań nie przenosić ekstrakcji ZIP poza globalny lock.
