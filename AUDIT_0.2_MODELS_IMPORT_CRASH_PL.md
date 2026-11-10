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


## Punkt 3 — regresja po poprawce cache (2026-11-10)

Dodatkowy problem wykryty podczas przeglądu: import może zastąpić konfigurację dostawcy, a identyfikator dostawcy może zostać ponownie użyty dla innego adresu API. Samo unieważnienie cache przy imporcie nie wystarcza jako ostatnia linia obrony, jeśli odczyt katalogu trafi na dane historyczne lub częściowo odtworzone.

Wdrożono:
- `ProviderCatalogCachePolicy` — katalog jest widoczny wyłącznie, gdy identyfikator dostawcy nadal istnieje, a znormalizowany Base URL cache odpowiada bieżącej konfiguracji.
- Przy niepewnym stanie magazynu albo błędzie odczytu konfiguracji katalog nie jest pokazywany (fail closed).
- 6 testów polityki: zgodny endpoint, ponownie użyty identyfikator z innym endpointem, usunięty dostawca, brak endpointu w starym cache, normalizacja wielkości liter/slash końcowych i istotność ścieżki endpointu.

Commit: `d28f82f5aab4934e69fb1331830c0417def0e80c`
CI dla tego SHA:
- Android Build: SUCCESS — run `42880766072`.
- Gradle Wrapper Validation: SUCCESS — run `42880765703`.
- Raport JUnit z artefaktu `13794530933`: **983 testy w 106 zestawach, 0 failures, 0 errors, 0 skipped**.
- Wybrane zestawy: BackupCrypto 20/20, ChatHistoryStore 23/23, ChatResponseArchive 15/15, ProviderCatalogCachePolicy 6/6, ProviderCatalogSyncGeneration 4/4, ProviderCatalogSyncRunner 5/5.

Status punktu 3: **IN PROGRESS**. Pokrycie jednostkowe dla szyfrowania/parsowania kopii, katalogu i trwałości historii rozmów jest potwierdzone CI. Nadal wymagają osobnego potwierdzenia ścieżki interfejsu importu na urządzeniu: import bazy, wejście w Modele, Czat i Rozmowy po imporcie, w tym brak zamknięcia aplikacji. Test urządzeniowy i logcat: NOT RUN.


## Dalsza regresja — odczyt identyfikatora ostatniego czatu (2026-11-10)

W przeglądzie nawigacji wykryto, że przejście do głównego czatu odczytywało `last_user_chat_id` bezpośrednio z `SharedPreferences`, poza wspólną blokadą i kontrolą stanu magazynu historii.

Wdrożono:
- `ChatHistoryStore.readLastUserChatId()` — odczyt przez `SharedPreferencesWriteHealth.withHealthyAccess`.
- Jeśli preferencje są niedostępne albo magazyn został objęty kwarantanną po niejednoznacznym zapisie, metoda zwraca `null`.
- Nawigacja do czatu w `MainActivity` zatrzymuje się z komunikatem zamiast generować zastępczy identyfikator i ryzykować rozjazd historii.
- Dodano 3 testy: poprawny odczyt ID, wyjątek przy odczycie preferencji, odczyt po niejednoznacznym zapisie/kwarantannie.

Commit kodu: `af410dceae0ca446819f95f1b4388be07c1f77d6`
CI dla dokładnie tego SHA:
- Android Build: SUCCESS — run `42885596457`; wszystkie kroki, w tym unit tests, assemble release APK i upload artefaktów: SUCCESS.
- Gradle Wrapper Validation: SUCCESS — run `42885596202`.
- Raport JUnit z artefaktu `13796336038`: **986 testów w 106 zestawach, 0 failures, 0 errors, 0 skipped**.
- `ChatHistoryStoreTest`: 26 testów (wcześniej 23); `ProviderCatalogCachePolicyTest`: 6; `ChatResponseArchiveTest`: 15; `SecureStoreImportTest`: 28.

Artefakty CI:
- Raport testów: `aiforteca-unit-test-reports`.
- Projekt źródłowy: `aiforteca-source-project`.
- APK: `aiforteca-release-apk`.

Uwaga o APK: workflow wyprodukował `app-release-unsigned.apk`. Build się powiódł, ale ten plik nie jest podpisany kluczem wydania i nie należy traktować go jako gotowego APK do instalacji/odbioru. Trwały klucz i podpisywanie pozostają osobnym punktem 4 planu; nie wygenerowano ani nie ujawniono żadnego klucza.

Status punktu 3 pozostaje **IN PROGRESS**: testy automatyczne CI przeszły, lecz test na fizycznym urządzeniu (import → Modele → Czat → Rozmowy, obserwacja logcat) nadal **NOT RUN**.


## Dalsza regresja — atomowe przełączanie rozmów (2026-11-10)

W przeglądzie ścieżki otwierania rozmowy wykryto ryzyko niespójności: `openChat()` wcześniej modyfikował aktywne kolekcje wiadomości/aktywności przed potwierdzeniem trwałego zapisu identyfikatora wybranej rozmowy. W przypadku niejednoznacznego błędu zapisu ekran i trwały wskaźnik bieżącej rozmowy mogły wskazywać różne stany.

Wdrożono:
- `ChatHistoryStore.selectChat()` — zatwierdza wybór rozmowy w kanonicznym dokumencie historii, a dla zwykłego czatu także `last_user_chat_id` w ramach wspólnej operacji zapisu.
- Jeśli wybrany identyfikator nie istnieje, tworzy poprawny pusty rekord rozmowy; nie nadpisuje uszkodzonej historii.
- `restoreMessagesForCurrentChat()` najpierw parsuje cały snapshot, następnie trwale zatwierdza wybór. Dopiero po udanym zapisie zmienia kolekcje w pamięci.
- `openChat()` korzysta z jednej ścieżki odtwarzania i nie wykonuje drugiego, redundantnego zapisu wyboru.
- Dodano 3 testy: atomowy zapis bieżącego ID i wskaźnika użytkownika, brak nadpisania ostatniego czatu użytkownika przez czat Asystenta oraz brak zmiany wskaźników przy uszkodzonej historii.

Commit kodu: `88a16b279672c5917ea3984852c7b61a0cfadb36`.
CI dla dokładnie tego SHA:
- Android Build: SUCCESS — run `42892044049`; wszystkie kroki, w tym unit tests, assemble release APK, pakowanie projektu źródłowego i upload artefaktów: SUCCESS.
- Gradle Wrapper Validation: SUCCESS — run `42892044062`.
- Raport JUnit z artefaktu `13798612154`: **989 testów w 106 zestawach, 0 failures, 0 errors, 0 skipped**.
- `ChatHistoryStoreTest` zawiera teraz testy selekcji rozmowy razem z wcześniejszymi testami trwałości i odtwarzania historii.

Artefakty:
- Raport testów: `aiforteca-unit-test-reports` (artifact ID `13798612154`).
- Projekt źródłowy: `aiforteca-source-project` (artifact ID `13800030224`).
- APK: `aiforteca-release-apk` (artifact ID `13798672387`).

APK nadal jest `app-release-unsigned.apk` (1 341 962 bajty). Build release przechodzi, ale artefakt nie jest podpisany kluczem wydania i nie jest gotowym pakietem instalacyjnym do odbioru. Punkt 4 — trwały klucz i konfiguracja podpisywania — pozostaje osobnym krokiem i wymaga uzgodnienia z użytkownikiem.

Status punktu 3: **IN PROGRESS**. Testy automatyczne dla dokładnego SHA przeszły. Test urządzeniowy po imporcie bazy (import → Modele → Czat → Rozmowy, potwierdzenie braku zamknięcia aplikacji i kontrola logcat) pozostaje **NOT RUN**.
