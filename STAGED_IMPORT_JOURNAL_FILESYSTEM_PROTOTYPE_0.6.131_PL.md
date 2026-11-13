# Staged Import — hostowy prototyp wersjonowanego journalu 0.6.131

## Status i granice

**HOST FILESYSTEM PROTOTYPE ONLY.** Nowy harness wykonuje rzeczywisty zapis/odczyt pliku na filesystemie hosta JVM. Nie jest produkcyjnym journalem Android, nie jest podłączony do `ProjectWorkspaceStore` ani istniejącego swap journal i nie dowodzi odporności na utratę zasilania, kernel crash, zachowania konkretnego filesystemu Android lub `fsync` na urządzeniu.

Produkcja pozostaje bez zmian: `ProjectWorkspaceStore.importZip()` nadal działa pod globalnym `ProjectWorkspaceOperationLock`. Nie dodano drugiego źródła prawdy.

## Format prototypu

Wersjonowany rekord UTF-8:

```text
format=1
id=<bounded-safe-id>
stage=<known-state>
sha256=<lowercase-hex-digest-of-exact-payload-bytes>
```

- Dozwolone stany: `PREPARED`, `COMMITTING`, `SWAPPED_AWAITING_EVIDENCE`, `COMMITTED`, `ABORTED`.
- Identyfikator ograniczony do `[A-Za-z0-9_-]{1,80}`.
- Odczyt ograniczony do 4096 bajtów; nieznana wersja, stan, brak checksum, uszkodzony checksum, niepoprawne pola i ucięty rekord są odrzucane (fail-closed).
- Zapis trafia najpierw do `import.journal.tmp`; kanał pliku jest flushowany przez `FileChannel.force(true)`, a następnie plik jest zastępowany przez `ATOMIC_MOVE`.
- Gdy filesystem nie obsługuje atomowego rename, prototyp odmawia fallbacku do nieatomowego `move`.
- Flush katalogu po rename jest best-effort i nie jest traktowany jako gwarancja platformowa.

## Fault matrix hostowa — 8/8 PASS

1. Round-trip wersjonowanego rekordu.
2. Atomowa podmiana ujawnia kompletny nowy rekord.
3. Wstrzyknięty wyjątek przed rename zachowuje poprzedni journal.
4. Zmiana payloadu bez aktualizacji checksum jest odrzucana.
5. Ucięty rekord jest odrzucany.
6. Nieznana wersja formatu jest odrzucana.
7. Nieznany stan jest odrzucany.
8. Niebezpieczny identyfikator jest odrzucany przed zapisem.

Harness: `tools/staged_import_journal_filesystem_harness/Runner.kt` i `run.sh`.

## Niezamknięte wymagania

- Nie ma testu rzeczywistego procesu zabitego pomiędzy `force`, rename i directory flush.
- Nie ma gwarancji power-loss durability; `FileChannel.force(true)` i directory fsync muszą być zweryfikowane na docelowym Android API/filesystemie.
- Nie ma obsługi dwóch writerów ani CAS/generacji rekordu; przed produkcją wymagany jest pojedynczy właściciel pod wspólną blokadą oraz jawna kontrola monotonicznej generacji.
- Nie ma migracji/kompatybilności ze swap journal `ProjectWorkspaceStore`; produkcja nie może utrzymywać dwóch konkurencyjnych journalów.
- Nie ma Android/JUnit/Robolectric/process-death testów.

**Wniosek:** prototyp potwierdza podstawowy kontrakt kodowania, walidacji i atomowego zastąpienia pliku na bieżącym hoście. Nie uprawnia do przeniesienia ekstrakcji ZIP poza global lock. Następny krok to audyt istniejącego swap journal i zaprojektowanie pojedynczego, wersjonowanego źródła prawdy wraz z kontrolą generacji i testami awarii na rzeczywistym Android filesystem.
