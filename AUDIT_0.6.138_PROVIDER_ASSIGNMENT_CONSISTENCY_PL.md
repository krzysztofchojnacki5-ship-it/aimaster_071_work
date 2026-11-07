# AI MASTER PRO — audyt spójności konfiguracji i przypisań agentów

Data: 2026-11-07
Gałąź: `audit/0.6.137-routing-hardening`
Bazowy commit kodu: `c787f22841be0a5de451b3fdbdf4a4fe8463e2e9`
Checkpoint referencyjny: `66cee89b`
Zakres: SecureStore, agent_provider_* w chat_state, import konfiguracji, odtwarzanie po restarcie.

## Stan repozytorium i CI

- Porównanie `main...audit/0.6.137-routing-hardening`: 388 ahead / 0 behind.
- HEAD main: `752149d40186144b0f458a32b5826c8928349c3a`.
- HEAD gałęzi: `c787f22841be0a5de451b3fdbdf4a4fe8463e2e9`.
- Workflow Android audit #109 / run `42331335074`: `in_progress`.
- Job `Unit tests and debug compilation`: setup JDK 17, Android SDK/API 36 i Gradle 8.13 zakończony sukcesem; krok uruchomienia testów i kompilacji nadal trwa. Nie oznaczać testów jako PASS przed zakończeniem joba.

## Potwierdzone zabezpieczenia SecureStore

1. `provider_ids` jest kopiowane do niezależnego Set; wyjątek w `getStringSet` ustawia procesowy latch niepewnego magazynu.
2. Wyjątek przy odczycie `key_*` przez `getString` ustawia ten sam latch. Test sprawdza, że zaszyfrowany wpis pozostaje nienaruszony.
3. `allConfigs()` odrzuca całą listę, jeśli podczas odczytu dowolnego profilu wystąpi błąd — nie zwraca częściowej listy.
4. Po ustawieniu latcha odczyty i zapisy wszystkich instancji SecureStore są blokowane do restartu procesu.
5. Import pełnej konfiguracji przygotowuje dane przed pojedynczym commitem; wykrywa duplikaty ID, błędne relacje rodziców i niedostępne istniejące sekrety.

Testy regresyjne dla wyjątków odczytu są obecne w `SecureStoreImportTest.kt`; ich wynik zależy od runu CI.

## Znaleziska — spójność przypisań

### F-01 — dwa źródła prawdy dla profilu MASTER
Priorytet: HIGH

`selectedProviderId` i `agentProviders[MASTER]` przechowują ten sam wybór w dwóch polach pamięci i dwóch kluczach SharedPreferences (`selected_provider` oraz `agent_provider_MASTER`). Odczyty nie są jednolite:
- `activeConfig()` wybiera `selectedProviderId`;
- `sendChatMessage()` wybiera `agentProviders[MASTER]` z fallbackiem do `selectedProviderId`;
- część ekranów modelu opiera zaznaczenie na `selectedProviderId`.

W normalnych ścieżkach zapisu wartości są zwykle aktualizowane razem, ale istnieją osobne operacje edycji i brak jednego kanonicznego resolvera. Po częściowym/nieaktualnym zapisie mogą wskazywać różne profile.

### F-02 — przypisania agentów nie są zatwierdzane trwale przed komunikatem sukcesu
Priorytet: HIGH

Zapis `agent_provider_<ROLE>` używa `SharedPreferences.Editor.apply()`. UI natychmiast aktualizuje mapę w pamięci i pokazuje komunikat „przypisano”, chociaż `apply()` nie potwierdza synchronicznego trwałego zapisu na dysku. To odstaje od nowych gwarancji SecureStore i TaskCheckpointStore.

### F-03 — import pełnej konfiguracji nie uzgadnia przypisań agentów
Priorytet: HIGH

Ścieżka importu wywołuje `store.replaceAllConfigs(configs)`, po czym ustawia wyłącznie `selectedProviderId`. Nie aktualizuje w tym miejscu `agentProviders`, `selected_provider` ani `agent_provider_*`. Po zastąpieniu zestawu profili istniejące przypisania mogą wskazywać na ID, których już nie ma. `showAgents()` pokazuje wtedy pierwszy profil w spinnerze jako wizualny fallback, ale nie naprawia trwale przypisania.

### F-04 — test połączenia agenta zmienia przypisanie przed wynikiem testu
Priorytet: MEDIUM

Akcja „Testuj połączenie agenta” zapisuje wybrany profil do mapy i SharedPreferences przed rozpoczęciem testu. Nieudany test pozostawia nowy profil przypisany, mimo że użytkownik nie wybrał akcji „Zapisz przypisanie”. Wymaga decyzji produktowej; bezpieczniejsza semantyka to testowanie kandydata bez mutacji przypisania, a zapis wyłącznie przez jawne potwierdzenie.

### F-05 — odczyt nieznanego/usuniętego ID nie ma jawnej normalizacji
Priorytet: MEDIUM

Przy starcie wartości przypisań są odczytywane z `chat_state` bez sprawdzenia ich obecności w aktualnym `provider_ids`. Brak profilu skutkuje późniejszym `loadProvider() == null`, a nie kontrolowanym uzgodnieniem stanu.

## Plan naprawczy — bez migracji SQLite

1. Wprowadzić jeden resolver profilu MASTER; usunąć rozbieżność między `selectedProviderId` i `agentProviders[MASTER]` albo utrzymywać oba wyłącznie przez jedną funkcję.
2. Dodać mały, testowalny komponent zarządzający przypisaniami: walidacja roli, ID, dostępności profilu i bezpieczny fallback.
3. Zapis przypisań wykonać jako jeden `SharedPreferences.commit()`; aktualizować mapę UI dopiero po potwierdzonym sukcesie. Przy wyjątku/false zachować poprzednie przypisanie i jawnie zgłosić błąd.
4. Po imporcie pełnego zestawu profili uzgodnić wszystkie role w jednym zapisie: zachować poprawne ID, a brakujące wskazać na wybrany/bezpieczny profil; nie tworzyć fikcyjnych profili.
5. Rozdzielić test kandydata od zapisu przypisania.
6. Dodać testy: rozbieżny MASTER, brakujące ID po imporcie, nieudany commit przypisania, wyjątek commit, test połączenia nie zmieniający przypisania, zachowanie poprawnych przypisań innych ról.
7. Dopiero po przejściu testów ocenić potrzebę dalszej zmiany modelu trwałości. Nie wdrażać migracji SQLite w tym etapie.

## Ograniczenia

- Ten dokument zapisuje wyniki przeglądu statycznego; nie jest potwierdzeniem wykonania nowych testów.
- Nie zmieniono `main`, wersji aplikacji, APK ani danych użytkownika.
- Nie wykonywano migracji SQLite.


---

## Aktualizacja 0.6.138 — pierwszy mały przyrost

Aktualny commit po zmianach: `d8127b1efb25948751bf65f10ae0f275b8783e26`.

### F-01 — kanoniczny resolver MASTER

Wprowadzono `MasterProviderSelection` jako czysty, niezależny od Androida resolver:
- niepuste `agentProviders["MASTER"]` jest źródłem kanonicznym;
- `selected_provider` jest używany wyłącznie jako fallback zgodności dla starszego stanu;
- brak obu wartości daje `null`, bez wymyślania identyfikatora.

Resolver podłączono do:
- `activeConfig()`;
- wyboru profilu w wysyłaniu wiadomości MASTER;
- fallbacku profilu MASTER dla zadań agentów;
- podświetlania aktywnego profilu MASTER w ekranach modeli;
- analizy jakości uruchamianej przez MASTER.

Pola legacy nie zostały jeszcze usunięte z pamięci ani SharedPreferences — to celowe ograniczenie ryzyka migracyjnego. Ich zapis pozostaje kompatybilny, ale odczyt routingu korzysta ze wspólnego resolvera.

Dodano `MasterProviderSelectionTest` z czterema przypadkami: rozbieżność wartości, fallback legacy, puste przypisanie kanoniczne oraz brak poprawnego wyboru.

### Stan pozostałych znalezisk po inspekcji HEAD

- F-02: ścieżka zapisu używa `SharedPreferencesWriteHealth.commit`; mapa pamięci jest aktualizowana dopiero po sukcesie. Testy awarii są obecne w `AgentProviderAssignmentStoreTest`.
- F-03: pełny import wykonuje `replaceAllConfigs`, a następnie `reconcileAfterProviderImport`; wszystkie role i lustro MASTER są zapisywane jednym commitem. Po niepowodzeniu aplikacja blokuje routing.
- F-04: „Testuj połączenie agenta” testuje kandydata i nie wywołuje zapisu przypisania. Jawny zapis jest osobną akcją.
- F-05: startup wykonuje walidację i uzgodnienie ID względem katalogu; przy błędzie typu lub niepewnym magazynie routing pozostaje zablokowany.

### Weryfikacja

- Przegląd źródeł na HEAD: wykonany.
- Nowy test jednostkowy: dodany, **nieuruchomiony**.
- Android Gradle build: **nieuruchomiony**.
- JUnit/Robolectric: **nieuruchomione**.
- GitHub Actions dla nowego commita: brak powiązanego workflow run; combined status zwrócił pustą listę.
- APK: nie tworzono.
- Companion: nieaktywowany.

Następny krok: uruchomić testy resolvera i istniejący zestaw `AgentProviderAssignmentStoreTest` w Android Studio/CI, a następnie wykonać testy przeplotu importu z zapisem przypisania. Nie wprowadzać kolejnych blokad, dopóki testy nie wykażą konkretnego wyścigu.


## Aktualizacja 0.6.138 — audyt przeplotu importu i zapisu przypisania

### Zakres inspekcji

Przejrzano na HEAD `2bc5da0edb29553615eecdc1a08610f0fcc2745d`:
- `AgentProviderAssignmentStore.kt` i `AgentProviderAssignmentStoreTest.kt`;
- `SharedPreferencesWriteHealth.kt`;
- `SecureStore.kt` i `SecureStoreImportTest.kt`;
- ścieżkę importu w `MainActivity.kt`, funkcję `persistAgentProviderAssignment()` oraz wszystkie znalezione wywołania tej funkcji.

### Wynik

Nie potwierdzono rzeczywistego wyścigu wymagającego nowej blokady produkcyjnej.

1. Zapis przypisania i uzgadnianie przypisań korzystają z tego samego locka pliku preferencji `chat_state`. `save()` kończy zapis przez `SharedPreferencesWriteHealth.commit()`; odczyt/uzgodnienie jest objęte `withHealthyAccess()`. Operacje na tym pliku nie wykonują równoległych commitów poza sekcją krytyczną.
2. Import profili w `MainActivity` wykonuje kolejno: anulowanie aktywnej pracy, synchroniczne `replaceAllConfigs()`, a następnie synchroniczne `reconcileAgentAssignmentsAfterImport()`. Obie operacje są wykonywane w obsłudze akcji UI; nie ma w tej ścieżce przekazania importu do executora.
3. `persistAgentProviderAssignment()` jest wywoływane z akcji interfejsu (wybór modelu MASTER, zapis przypisania agenta oraz jawne przypisanie modelu). Nie znaleziono wywołania tej funkcji z workera w tle. Test połączenia agenta uruchamia worker, ale worker nie zapisuje przypisania; wynik wraca do UI.
4. Pozostaje krótki logiczny przedział między commitem wymiany profili w `secure_provider_store` a commitem uzgodnienia `chat_state`. W obecnym przepływie nie może go wykorzystać konkurencyjna akcja UI, ponieważ import i uzgodnienie wykonują się sekwencyjnie na wątku UI. Jeśli uzgodnienie nie powiedzie się, `agentAssignmentsHealthy` pozostaje `false`, mapy routingu są czyszczone, a wysyłanie zadań jest blokowane.
5. Dwa magazyny mają osobne locki i osobne commity; nie jest to transakcja atomowa między plikami. Ochroną na wypadek przerwania procesu pozostaje startup reconciliation. Nie należy opisywać tej pary commitów jako jednej transakcji.

### Decyzja

- Nie dodano globalnego locka ani kolejnej warstwy synchronizacji — byłaby to zmiana bez wykazanego współbieżnego wywołania, a mogłaby niepotrzebnie blokować operacje Keystore.
- Nie dodano testu, który sztucznie przeplatałby dwa wywołania spoza rzeczywistego modelu wywołań aplikacji. Istniejący test `saveWaitsForAssignmentReconciliationReadLockBeforeCommitting` nadal sprawdza serializację operacji na `chat_state`.
- Wniosek dotyczy przeanalizowanych call sites na tym HEAD; jeśli później zapis przypisań zostanie przeniesiony do workera lub import stanie się asynchroniczny, wymagany będzie ponowny audyt i test przeplotu obejmujący nowy model wykonania.

### Weryfikacja

- Inspekcja źródeł i call sites: wykonana.
- Nowe testy: nie dodano — brak potwierdzonego wyścigu w obecnym modelu wykonania.
- Testy JUnit/Robolectric i Android build: nieuruchomione.
- APK: nie tworzono.
- Companion: nieaktywowany.

Następny krok: przejść do następnego punktu 0.6.138 — sprawdzić awarie na granicy pełnego importu: sukces commitu `secure_provider_store` i niepowodzenie commitu uzgodnienia `chat_state`, w tym stan po ponownym uruchomieniu i startup recovery.


## Aktualizacja 0.6.138 — awaria między wymianą profili a odzyskiwaniem przypisań

### Potwierdzony przebieg

Prześledzono dwa niezależne punkty trwałego zapisu:
1. `SecureStore.replaceAllConfigs()` zapisuje nowy katalog profili, ich metadane i sekrety jednym commitem w `secure_provider_store`.
2. `AgentProviderAssignmentStore.reconcileAfterProviderImport()` zapisuje przypisania wszystkich ról i lustrzany `selected_provider` jednym commitem w `chat_state`.

Nie są to wspólne transakcje. Jeżeli drugi commit zwróci błąd/wyjątek, stan `chat_state` zostaje objęty latch'em niepewności, a aplikacja w bieżącym procesie blokuje routing. Po restarcie latch'e pamięciowe są odtwarzane od zera, a `MainActivity.onCreate()` najpierw odczytuje aktualny katalog przez `SecureStore.allConfigs()`, następnie uruchamia `reconcileExistingAssignments()`. Ta funkcja zachowuje przypisania nadal wskazujące na istniejące profile, naprawia brakujące ID, zapisuje role wraz z lustrem MASTER jednym commitem i zwraca null przy błędzie zapisu/odczytu. Przy null UI pozostaje w stanie `agentAssignmentsHealthy = false`.

### Zmiana testowa

Dodano test integracyjny Robolectric:
`startupRecoveryRepairsAssignmentsAfterImportReconciliationCommitFailure`.

Test:
- zapisuje nowy katalog profili przez rzeczywisty `SecureStore.replaceAllConfigs()`;
- symuluje niejednoznaczny commit `chat_state` (zastosowanie mapy SharedPreferences, po którym commit zgłasza false);
- symuluje granicę restartu przez wyczyszczenie wyłącznie procesowych latch'y niepewności;
- ponownie odczytuje katalog i wykonuje startup reconciliation;
- sprawdza zachowanie poprawnego przypisania CODER, naprawę nieaktualnego MASTER/RESEARCHER oraz spójność `selected_provider`.

Dodano także `@After` do resetowania latch'y testowych, aby awaria w jednym teście nie wpływała na kolejne testy klasy.

### Ograniczenia modelu testowego

To test odtwarzania obu możliwych stanów logicznych po niejednoznacznym commicie w pamięci Robolectric, a nie test rzeczywistego zabicia procesu Android ani gwarancja zachowania pliku XML na dysku po zaniku zasilania. Test odtwarza restart warstwy procesu, pozostawiając bieżący snapshot SharedPreferences. Nie symuluje fizycznej utraty lub częściowego zapisu pliku preferencji.

### Weryfikacja

- Inspekcja kolejności startup recovery i obu commitów: wykonana.
- Test integracyjny: dodany, **nieuruchomiony**.
- Pozostałe testy JUnit/Robolectric: **nieuruchomione**.
- Android Gradle build: **nieuruchomiony**.
- APK: nie tworzono.
- Companion: nieaktywowany.

Następny krok: rozszerzyć awaryjną macierz o drugi możliwy wynik niejednoznacznego commitu — stary snapshot przypisań pozostał na dysku — i sprawdzić, czy startup recovery naprawia go bez polegania na zmianach widocznych wyłącznie w pamięci procesu.


### Drugi wariant niejednoznacznego commitu — stary snapshot pozostał

Rozszerzono test helper `AmbiguousCommitContext` o parametr `applyBeforeFailure` (domyślnie `true`, zachowując dotychczasowe zachowanie wszystkich istniejących testów). Dzięki temu można symulować oba wyniki:
- nowy snapshot widoczny po `apply()`, mimo że commit zgłasza false;
- brak zastosowania edytora, czyli pozostawienie starego snapshotu przypisań.

Dodano test `startupRecoveryRepairsOldDurableAssignmentsWhenImportReconciliationCommitDidNotPersist`. Po udanym zastąpieniu katalogu profili i nieudanym commicie przypisań test pozostawia stare ID w `chat_state`, resetuje latch'e procesu i uruchamia startup reconciliation. Sprawdza, że wszystkie role zostają skierowane wyłącznie na ID istniejące w nowym katalogu.

### Zaobserwowana właściwość / ograniczenie

Jeśli stary snapshot `chat_state` przetrwa awarię, startup nie zna już `preferredMasterId` przekazanego do przerwanego importu — ta wartość nie jest zapisywana w `secure_provider_store`. Mechanizm wybiera więc deterministycznie pierwsze poprawne ID z katalogu, jeśli ani zapis MASTER, ani legacy `selected_provider`, ani domyślne ID MASTER nie są aktualne. W rzeczywistym `MainActivity.onCreate()` przed uzgodnieniem wykonywane jest jeszcze `seedDefaultProviders()`; dlatego przy braku zachowanej preferencji MASTER może wrócić do jednego z wbudowanych profili (zwykle Gemini), zamiast do pierwszego profilu importu. To zachowanie jest bezpieczne (nie kieruje do usuniętego profilu), ale może różnić się od preferencji importu.

Nie zmieniono jeszcze formatu magazynu ani nie dodano markera zamiaru importu. Taka zmiana wymaga osobnej decyzji, ponieważ marker musi mieć jasno określony cykl życia, aby nie nadpisywać późniejszego, świadomego wyboru użytkownika.

### Weryfikacja uzupełniająca

- Dwa warianty awarii commit: testy dodane.
- Startup recovery po starym i nowym snapshotcie: pokryte testami.
- Testy nieuruchomione; brak potwierdzenia JUnit/Robolectric.
- Android build nieuruchomiony; APK nie tworzono.


Test starego snapshotu został doprecyzowany tak, aby katalog testowy obejmował również profile domyślne Gemini i Groq, które aplikacja dodaje przed startup reconciliation. Oczekiwany wynik testu odpowiada więc rzeczywistemu fallbackowi przy utracie preferencji importu: MASTER wraca do Gemini, a role specjalistyczne do Groq, o ile te profile są obecne.


## Aktualizacja — odzyskiwanie po niejednoznacznym commicie SecureStore

Dodano testy w `SecureStoreReplacementRecoveryTest.kt` dla dwóch możliwych wyników nieudanego commitu `replaceAllConfigs()`:
- nowy katalog jest widoczny w pamięci po `apply()`, mimo że commit zgłasza false;
- stary katalog pozostaje bez zmian, gdy edytor nie został zastosowany.

Testy odtwarzają teraz także istotną część rzeczywistego startu `MainActivity`: po wyczyszczeniu latcha procesu dodawane są domyślne profile Gemini i Groq, a dopiero potem wykonywane jest `reconcileExistingAssignments()`. W scenariuszu, w którym nowy katalog przetrwał, ale stare przypisanie MASTER wskazuje na usunięty profil, oczekiwanym fallbackiem jest Gemini, a role specjalistyczne wskazują na Groq. Gdy stary katalog przetrwał, poprawne stare przypisania pozostają zachowane.

### Kontrola czterech obszarów

1. Odzyskiwanie po niejednoznacznym commicie katalogu: testy dla obu wyników commitu dodane.
2. Latch `SecureStoreHealth`: potwierdzono blokowanie kolejnych odczytów i zapisów w bieżącym procesie po niepewnym commicie lub błędzie odczytu; reset jest wyłącznie testową symulacją granicy procesu.
3. Częściowy import: `replaceAllConfigs()` waliduje i przygotowuje wszystkie wpisy oraz zaszyfrowane sekrety przed jednym końcowym commitem. Istniejące testy pokrywają m.in. duplikaty ID, błędny schemat, brakującego rodzica, cykle i niedostępne sekrety.
4. Spójność ról: startup reconciliation zapisuje wszystkie role i `selected_provider` jednym commitem; przy niepowodzeniu zwraca null, a startup pozostawia routing zablokowany.

### Status weryfikacji

- Kod testów: zapisany w commicie `b11fdebd35db63ece157256d99d3421fb0f94a7a`.
- CI `42411214421`: nadal `in_progress` na wcześniejszym SHA `7c2c38c06bf2e3e8abf5f33a66815b35e9f4bcc7`; nie obejmuje korekty testów z commita `b11fdebd`.
- Dla commita `b11fdebd` nie ma jeszcze powiązanego runu Actions. Nie oznaczono testów ani kompilacji jako PASS.
- Testy Robolectric nie symulują fizycznego zaniku zasilania ani rzeczywistego procesu Android; odtwarzają logiczne warianty stanu SharedPreferences i reset latchy.
- APK nie tworzono; Companion pozostaje nieaktywny.

Następny krok: uruchomić workflow Android audit dla aktualnego HEAD i dopiero po wyniku rozstrzygnąć, czy potrzebna jest poprawka testu lub kodu produkcyjnego.
