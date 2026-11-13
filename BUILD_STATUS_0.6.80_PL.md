# Build status — 0.6.80-SOURCE

- Parent: `AI_MASTER_PRO_0_6_79_COMPANION_ROTATION_CONCURRENCY_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK was produced.
- Cel: usunięcie luki fail-open po nieudanej destrukcyjnej mutacji magazynu zaufania Companion, gdy pojedynczy mechanizm trwałej blokady zawiedzie.

## Zmiany

- `CompanionProvisioningStore` używa teraz trzech barier:
  1. process-local `trustUnavailableAfterFailedMutation` — natychmiast zamyka odczyty w bieżącym procesie;
  2. trwały marker `TRUST_LOCK_KEY` w `SharedPreferences`;
  3. niezależny plik `companion_trust_unavailable.lock` w `Context.noBackupFilesDir`, publikowany przez zapis pliku tymczasowego + `fd.sync()` + atomowy `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`.
- Przy błędzie destrukcyjnej mutacji store próbuje zapisać oba trwałe markery i usunąć alias AES metadata. Każda wtórna awaria jest zachowywana jako `suppressed` na pierwotnym błędzie.
- Każdy odczyt pinów sprawdza oba markery. Nieudany odczyt stanu blokady również kończy się fail-closed. Plik jest sprawdzany przez `Files.readAttributes(..., NOFOLLOW_LINKS)`: brak pliku jest jedynym stanem interpretowanym jako brak bariery; inne błędy odczytu blokują zaufanie.
- `wipeAllTrust()` usuwa `PINS_KEY` i marker preferences w jednym `commit()`, następnie usuwa niezależny plik blokady. Process-local latch jest czyszczony dopiero po potwierdzeniu usunięcia pliku. Błąd usunięcia pliku pozostawia trust zablokowany i jest raportowany.
- Błąd usunięcia starego aliasu Keystore po potwierdzonym usunięciu payloadu i obu markerów nie przywraca starego ciphertextu; pozostaje raportowany jako błąd cleanup.
- Dodano `CompanionTrustPersistentLockTest` (Robolectric): marker plikowy blokuje świeżą instancję store po symulowanym restarcie; marker preferences samodzielnie blokuje odczyt.

## Wykonane sprawdzenia

- `kotlinc -Werror` dla wszystkich `Companion*.kt` + rzeczywistego `CompanionProvisioningStore.kt` z lokalnymi atrapami Android/JSON: PASS.
- Lokalny fault harness uruchomiony na rzeczywistych klasach źródłowych store/core: 4/4 PASS — marker plikowy po nowej instancji, usunięcie markera, niezależny marker preferences, nieczytelny stan blokady fail-closed.
- Regresja lokalna shim-JUnit: testy concurrency registry + pin rotation 6/6 PASS; to lokalny runner JVM, nie pełny JUnit/Gradle.
- Archiwum źródłowe: `unzip -t` PASS; SHA-256 jest raportowany przy przekazaniu paczki.

## Nie wykonano

- Gradle/JUnit/Robolectric: NOT RUN (brak potwierdzonego uruchomienia Gradle w tym środowisku). Nowe testy Robolectric są dodane do source tree, ale nie są raportowane jako wykonane.
- Testy na prawdziwym Android Keystore, fault injection rzeczywistego `SharedPreferences.commit()`, restart procesu Android, `assembleDebug`, APK i test urządzeniowy: NOT RUN.
- Nie uruchomiono listenera ani transportu Companion.

## Granica gwarancji

Po restarcie fail-closed jest zabezpieczony przez dwa niezależne trwałe markery oraz kryptograficzny fallback usunięcia aliasu. Nie można zagwarantować blokady po restarcie, jeśli jednocześnie zawiodą: zapis markera preferences, atomowa publikacja pliku blokady i usunięcie aliasu Keystore. W takim przypadku bieżący proces pozostaje zablokowany, a wyjątki są zachowane; nie wolno deklarować trwałej ochrony restartowej.
