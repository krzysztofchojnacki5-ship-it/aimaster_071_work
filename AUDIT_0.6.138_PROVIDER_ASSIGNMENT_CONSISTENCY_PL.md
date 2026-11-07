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
