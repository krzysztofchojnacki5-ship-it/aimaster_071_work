# Audyt awarii zakładki Modele po imporcie — AiFORTeca 0.2

Data: 2026-11-10
Branch: work/aiforteca-0.2-master-agent-foundation
Audytowany punkt planu: 1/23
Wersja aplikacji: 0.2 (versionCode 3)
Zakres: import zaszyfrowanej kopii dostawców/kluczy oraz wejście do ekranu Modele.

## Werdykt

Nie ma wystarczających danych, aby uczciwie wskazać jedną, potwierdzoną linię powodującą crash: brak stack trace z urządzenia. Audyt kodu pozwolił jednak zawęzić najbardziej podejrzaną ścieżkę i wykluczyć kilka oczywistych kandydatów. Nie oznaczam awarii jako naprawionej.

## Potwierdzone fakty w kodzie

1. Import zaszyfrowanej kopii przechodzi przez `BackupCrypto.decrypt`, `parseProviderConfigs` i `SecureStore.replaceAllConfigs`. Import waliduje wpisy, szyfruje klucze ponownie w Android Keystore i zatwierdza konfiguracje transakcyjnie.
2. Po imporcie kod usuwa `models_json`, `last_sync` i ustawia `last_attempt=0`. To celowo unieważnia lokalny katalog modeli, który nie jest częścią przenośnej kopii.
3. Następnie import odtwarza przypisania ról i natychmiast wywołuje `showShell("Modele"); showProviders()`.
4. `showProviders()` po zbudowaniu ekranu automatycznie uruchamia `refreshAllModelCatalogs(showToast=false)`, gdy `last_attempt == 0` lub katalog jest starszy niż 6 godzin. Ponieważ import ustawia `last_attempt=0`, po każdym imporcie uruchamiane jest automatyczne odpytanie wszystkich skonfigurowanych dostawców.
5. To odświeżanie działa w pojedynczym executorze. Wyniki katalogów są zapisywane przez `saveModelCatalogForProvider`, które dodatkowo aktualizuje cennik w `ApiTokenTracker`. Nie wszystkie operacje zapisu/aktualizacji cennika są osłonięte jednym wspólnym blokiem obsługi wyjątków.
6. `readModelCatalogEntries()` osłania parsowanie JSON przez `runCatching`; uszkodzony JSON katalogu nie powinien sam w sobie wywołać crasha.
7. `TokenTracker.getUsage()` używa bezpiecznego odczytu i parsowania licznika. Nie jest obecnie oczywistym źródłem crasha ekranu dostawców.
8. `loadProvider()` pobiera profile przez `SecureStore.readConfig`; profile podrzędne pobierają klucz i adres z profilu nadrzędnego. Błędy odczytu sekretu są zamieniane na stan niedostępnego klucza, a niejawnie nie podstawia się nowego klucza.

## Najbardziej podejrzany mechanizm

Automatyczne odświeżanie katalogów uruchamiane bezpośrednio po imporcie. Import zeruje znacznik ostatniej próby, a ekran Modele traktuje to jako sygnał do natychmiastowego odpytania wszystkich dostawców. To oznacza, że wejście do ekranu może jednocześnie uruchomić serię połączeń sieciowych i zapisów katalogu/cenników. Jest to potwierdzona zależność w kodzie, ale bez stack trace nie można stwierdzić, że właśnie ona jest bezpośrednią przyczyną zamknięcia aplikacji.

## Zakres punktu 2 — plan naprawy

- Rozdzielić renderowanie ekranu od automatycznej synchronizacji katalogu.
- Dodać pojedynczy mechanizm blokujący równoległe/powielone odświeżenia.
- Po imporcie nie uruchamiać natychmiastowego odpytania wszystkich dostawców; pokazać ekran stabilnie i wymagać jawnego odświeżenia albo uruchamiać je kontrolowanie po zakończeniu renderowania.
- Osłonić błędami operacje zapisu katalogu i synchronizacji cenników, tak aby błąd jednego dostawcy nie kończył całej synchronizacji.
- Dodać testy regresji dla importu, unieważnienia cache i automatycznego odświeżania.
- Nie zmieniać formatu kopii, identyfikatorów dostawców ani mechanizmu SecureStore bez wykazanej potrzeby.

## Status weryfikacji

- Audyt źródła: DONE.
- Reprodukcja na urządzeniu: NOT RUN — brak dostępu do urządzenia i logcat.
- Test regresji crasha: NOT RUN — zostanie dodany w punkcie 2.
- Build po audycie: NOT RUN — punkt 1 nie wprowadza zmian w kodzie aplikacji.
- Awaria: NIEPOTWIERDZONA JAKO NAPRAWIONA.

## Źródło

Repozytorium: krzysztofchojnacki5-ship-it/aimaster_071_work
Pliki przeanalizowane:
- app/src/main/java/pl/aimasterpro/app/MainActivity.kt
- app/src/main/java/pl/aimasterpro/app/data/SecureStore.kt
- app/src/main/java/pl/aimasterpro/app/data/BackupCrypto.kt
- app/src/main/java/pl/aimasterpro/app/core/TokenTracker.kt
- app/build.gradle.kts


## Wynik punktu 2 — poprawki i CI (2026-11-10)

Commit końcowy: `674b6202e5ca2325a2eab26f7780bf703da32b5c`
Zakres zmian: kontrolowane odświeżanie katalogów dostawców, izolacja błędów, anulowanie nieaktualnych synchronizacji po imporcie, atomowość wymiany konfiguracji względem snapshotów oraz zabezpieczenie ponownego odświeżenia w ścieżce `finally`.

Weryfikacja dla dokładnie powyższego SHA:
- Android Build: SUCCESS — run `42879134762`.
- Gradle Wrapper Validation: SUCCESS — run `42879134573`.
- Kroki Android Build: unit tests, assemble release APK, package source project i upload artefaktów zakończone SUCCESS.
- Raport JUnit pobrany z artefaktu `13793716127`: **977 testów w 105 zestawach, 0 failures, 0 errors, 0 skipped**.
- Testy `ProviderCatalogSyncRunnerTest` oraz `ProviderCatalogSyncGenerationTest` są częścią raportu tego commita; szczegółowe scenariusze obejmują izolację błędów dostawców, anulowanie i ochronę przed zapisem wyników starej generacji.
- Artefakty CI: `aiforteca-source-project`, `aiforteca-unit-test-reports`, `aiforteca-release-apk`.

Status punktu 2: **DONE — walidacja CI dla końcowego commita zakończona powodzeniem**.

Ograniczenie: test urządzeniowy po imporcie zaszyfrowanej bazy i obserwacja logcat nadal są NOT RUN. Dlatego nie stwierdzamy, że pierwotny crash został definitywnie odtworzony i usunięty; poprawki zabezpieczają podejrzaną ścieżkę synchronizacji, ale potwierdzenie na urządzeniu pozostaje wymagane w punkcie 3 / testach końcowych.
