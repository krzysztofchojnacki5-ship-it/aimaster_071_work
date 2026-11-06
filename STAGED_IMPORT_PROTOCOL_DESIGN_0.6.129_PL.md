# Projekt protokołu staged import — 0.6.129

## Status

**SPECIFICATION / HOST CONTRACT MODEL ONLY.** Produkcyjny importer pozostaje bez zmian. `ProjectWorkspaceStore.importZip()` nadal konsumuje ZIP, waliduje pliki, tworzy snapshot i wykonuje swap w jednej metodzie `@Synchronized`, a caller utrzymuje `ProjectWorkspaceOperationLock`. Nie wolno traktować testów modelu jako testów produkcyjnego importu.

## Dlaczego nie przenosimy dziś ekstrakcji poza blokadę

Aktualny import ma jeden obszar odpowiedzialności: wejściowy `InputStream` → ograniczona ekstrakcja → manifest → walidacja bieżącego workspace → snapshot → `replaceCurrent()` → prune snapshotów. Proste przeniesienie `extract()` przed `withLock` stworzyłoby okno TOCTOU: stan, który został sprawdzony przed importem, mógłby zostać zmieniony przez patch, restore albo recovery przed swapem. Dodatkowo obecne metody store są w wielu miejscach `@Synchronized`; nowa metoda synchronizowana, która wewnętrznie czeka na globalny lock, mogłaby odwrócić kolejność monitor/store ↔ workspace gate.

## Docelowy kontrakt dwóch faz

### Faza A — prepare (poza globalnym workspace lock)

1. Caller otwiera URI stream i przekazuje go do `prepareImport`.
2. Store tworzy losowy, nieprzewidywalny `stagingId`; katalog stagingu i metadane mają prywatne, kanoniczne położenie pod rootem workspace.
3. Ekstrakcja zachowuje obecne limity: 40 MB archiwum, 80 MB rozpakowane, 8 MB pojedynczy plik, 3000 wpisów, filtrowanie sekretów/katalogów, normalizację ścieżek, odrzucanie duplikatów i traversal.
4. Store buduje manifest i zapisuje metadane przygotowania: wersja protokołu, stagingId, owner token, createdAt, liczby/rozmiary oraz digest manifestu. Token jest opaque i jednorazowy.
5. Po zakończeniu prepare nie wolno już modyfikować drzewa stagingu. Store musi zweryfikować brak symlinków, dokładny zestaw plików, rozmiary i SHA-256. Przygotowanie nie odczytuje ani nie zapisuje `current/`, snapshotów ani receiptów.
6. Błąd prepare usuwa wyłącznie własny staging. Jeśli cleanup zawiedzie, katalog pozostaje orphanem do bezpiecznego cleanupu.

### Faza B — commit (wewnątrz globalnego workspace lock)

1. Caller zdobywa `ProjectWorkspaceOperationLock.withLock` i dopiero wtedy wywołuje `commitPreparedImport(token)`.
2. Commit sprawdza token, owner, wersję protokołu, lokalizację kanoniczną, brak symlinków, status jednorazowego użycia oraz manifest/tree digest. Weryfikacja stagingu jest powtarzana pod lockiem, aby zamknąć TOCTOU.
3. Pod lockiem następuje recovery istniejącego swap journal i sprawdzenie pending transaction/receipt według obowiązującej polityki workspace.
4. Bieżący workspace jest sprawdzany **w chwili commit**, nie w chwili prepare. Jeżeli patch/restore zmienił go w czasie ekstrakcji, import nie używa starej identity. Import jest operacją zastępującą: może zastąpić nowy, zdrowy workspace, ale przed swapem musi wykonać snapshot dokładnie tego aktualnego stanu. Jeśli aktualny workspace jest niezdrowy, commit fail-closed.
5. Dopiero po wszystkich walidacjach tworzony jest snapshot aktualnego `current/`, po czym staging jest przekazywany do istniejącego journaled `replaceCurrent()` i wykonywane jest prune snapshotów.
6. Token jest oznaczany jako zużyty w sposób odporny na replay. Samo usunięcie katalogu stagingu nie może być jedynym dowodem zużycia, jeśli proces może umrzeć na granicy rename/journal.
7. W tej samej sekcji krytycznej caller unieważnia `HostActiveChangeSetStore` pointer, tak jak obecnie. Nie wolno rozdzielać swapu i invalidacji evidence.

## Własność i współbieżność

- `stagingId` + losowy token wiążą przygotowanie z konkretnym callerem/operacją. Token nie może być odtwarzany z nazwy katalogu.
- Każdy prepare ma osobny staging. Dwa importy nie mogą współdzielić katalogu ani manifestu.
- Żadna metoda `@Synchronized` nie może pobierać globalnego locka wewnątrz swojego monitora. Zalecany podział: metody fazy A operują wyłącznie na prywatnym stagingu i własnym, wąskim monitorze; commit jest jawnie `...Locked` i caller zdobywa globalny gate przed wejściem do store. Trzeba udokumentować kolejność locków i objąć ją source guardem.
- Jeśli commit czeka na globalny gate, staging musi mieć lease/TTL wystarczający na kolejkę; cleanup nie może usuwać aktywnego stagingu. Preferowany cleanup wyłącznie przy starcie pod workspace lock, z wiekiem/markerem ownera i walidacją stanu journal.

## Awaria i odzyskiwanie

| Punkt awarii | Wymagana semantyka |
|---|---|
| śmierć procesu w prepare | osierocony staging bez wpływu na `current/`; cleanup po TTL |
| błąd ekstrakcji / limit / digest | abort własnego stagingu; brak snapshotu i swapu |
| śmierć po prepare, przed commit | prepared staging może być wznowiony tylko jeśli token/lease nadal ważny; inaczej cleanup |
| commit odrzucony przed snapshotem | `current/` bez zmian; token może być abortowany, brak częściowego importu |
| śmierć podczas snapshotu | istniejący mechanizm snapshotu i startup recovery musi pozostawić stary `current/` nienaruszony |
| śmierć podczas `replaceCurrent` | istniejący swap journal rozstrzyga recovery; nie tworzyć równoległego, konkurencyjnego journalu |
| śmierć po swapie, przed evidence invalidation | commit musi być rozpoznawalny jako zakończony; startup reconciliation musi unieważnić stary pointer zanim evidence będzie dostępne |
| ponowiony commit | odrzucenie replay; nigdy drugi snapshot/swap z tego samego tokena |

## Wymagane testy przed wdrożeniem

1. Granice limitów ZIP i wszystkie istniejące ścieżki traversal/duplicate/symlink.
2. Token obcy, zmodyfikowany, wygasły, zużyty, replay i podwójny commit.
3. Dwa równoległe prepare; prepare A nie może nadpisać/abortować B.
4. Prepare równolegle z patch, restore, rollback i recovery; commit zawsze rewaliduje aktualny workspace pod globalnym lockiem.
5. Commit przy zdrowym workspace zmienionym po prepare — snapshot nowego stanu i zastąpienie go importem.
6. Commit przy uszkodzonym workspace — fail-closed bez swapu.
7. Fault injection na każdej granicy snapshot/journal/rename/receipt/pointer invalidation.
8. Restart/process-death cleanup: orphan-only, prepared-valid, commit-in-progress, committed-awaiting-evidence-invalidation.
9. Lock-order test: brak ścieżki store monitor → global gate; brak I/O ZIP i callbacków UI/network pod global gate.
10. Android/Robolectric testy rzeczywistego filesystemu i restartu procesu; hostowy model nie jest ich zamiennikiem.

## Decyzje jawne

- Import może zastąpić workspace, który zmienił się od czasu prepare, o ile stan w chwili commit jest zdrowy i zostanie zrobiony jego snapshot. To zachowuje semantykę importu jako jawnej operacji zastąpienia, bez używania nieaktualnej identity.
- Import nie może zastąpić workspace z niezgodnością manifestu.
- Ekstrakcja poza globalnym lockiem jest celem wydajnościowym, nie warunkiem bezpieczeństwa. Do czasu pełnego wdrożenia kontraktu pozostaje pod lockiem.
- Wersja aplikacji pozostaje `0.6.54 / code 80`; ten dokument nie zmienia zachowania runtime.
