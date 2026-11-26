# AiFORTeca — rejestr pakietów prac

Aktualizacja: 2026-11-15

## Pakiet 1 — autoryzacja i diagnostyka dostawców

- Gałąź: `work/aiforteca-0.2-master-agent-foundation`
- Commit kodu: `9d1403a9efb95d19a4e4fc1ed92925b62d9a8ad1`
- Commit kontrolny CI: `efae16cda5305833b3c9b4dc5feccadb69b72062`
- Zakres: normalizacja kluczy API i prefiksu Bearer; wskazówki dla HTTP 400/401; redakcja sekretów w diagnostyce; testy polityki nagłówków i komunikatów błędów.
- Wynik CI: `AiFORTeca Unit Tests` — SUCCESS; `Gradle Wrapper Validation` — SUCCESS.
- APK / pełny build Android: niepotwierdzone. Nie blokuje to dalszych prac zgodnie z decyzją użytkownika.

## Pakiet 2 — rozdzielenie profili dostawców i modeli

- Baza: commit kontrolny pakietu 1 `efae16cda5305833b3c9b4dc5feccadb69b72062`
- Ostatni commit kodu pakietu: `48536d022a6f738fabb59c72c957d48ea200c1d9`
- Zakres czterech zmian:
  1. Wprowadzono osobne typy domenowe `ProviderProfile` (połączenie i referencja do sekretu) oraz `ModelProfile` (model i jego typ uruchomienia).
  2. Dodano nieinwazyjny konwerter starych `ProviderConfig`; zachowuje istniejące ID profili, ID przypisań agentów i referencję do klucza w SecureStore.
  3. Rozróżniono `LOCAL_OLLAMA` od `REMOTE_API` na podstawie zweryfikowanego endpointu, a nie nazwy modelu.
  4. Dodano atomową migrację katalogów bez kopiowania kluczy API oraz uruchomienie migracji przy starcie aplikacji; stare rekordy pozostają źródłem prawdy na czas przejściowy.
- Testy dodane: mapowanie profili, zachowanie ID i referencji sekretu, lokalny Ollama kontra API, osierocone profile, brak sekretów w nowym magazynie, idempotencja i pusty magazyn.
- CI: końcowy przebieg `AiFORTeca Unit Tests` na HEAD dokumentacyjnym `b947b400e54dbf0454dc79530a746a701d93d91f` — SUCCESS (run `43710533929`); `Gradle Wrapper Validation` — SUCCESS (run `43710533945`). Raport testów opublikowano jako artefakt `14160168314` (`aiforteca-unit-test-reports`).
- Status: testy jednostkowe pakietu 2 i walidacja wrappera zaliczone. `assembleDebug`, inspekcja metadanych APK i test urządzenia nie są potwierdzone; ten workflow nie publikuje APK.

## Zasady

- Cztery zmiany kodu na pakiet, następnie pełne testy CI.
- APK nie jest warunkiem kontynuacji prac, jeśli użytkownik wyraźnie polecił nie czekać; status buildu i APK nadal raportować osobno i wyłącznie na podstawie artefaktów.
- Nie oznaczać testów, buildu ani APK jako zaliczonych bez potwierdzenia z CI / artefaktów.

### Korekta po pierwszym przebiegu CI pakietu 2

- Commit korekty: `48536d022a6f738fabb59c72c957d48ea200c1d9`.
- Przyczyna niepowodzenia: test wykazał, że migrator błędnie oznaczał poprawny rekord dostawcy bez domyślnego modelu jako pominięty.
- Poprawka: rekord główny dostawcy bez modelu jest zachowywany jako połączenie; pusty model w profilu potomnym nadal jest raportowany jako pominięty.
- Pierwszy przebieg na `9e0ef3c` wykonał 1299 testów: 1298 zaliczonych, 1 niezaliczony. Błąd dotyczył oczekiwania testu dla rekordu głównego dostawcy bez modelu.
- Poprawkę kodu wprowadzono w `48536d022a6f738fabb59c72c957d48ea200c1d9`. Nowszy przebieg na HEAD `b947b400e54dbf0454dc79530a746a701d93d91f` zakończył testy sukcesem (run `43710533929`); walidacja wrappera również SUCCESS (run `43710533945`). Poprzednie runy `43710507395` i `43710503509` są anulowane/zastąpione i nie stanowią wyniku końcowego.


## Potwierdzenie końcowe pakietu 2 — 2026-11-15
- Aktualny HEAD PR #3 sprawdzony: `b947b400e54dbf0454dc79530a746a701d93d91f`.
- `AiFORTeca Unit Tests` run `43710533929`: SUCCESS; artefakt raportu `aiforteca-unit-test-reports` ID `14160168314` (SHA-256 `3fe75cea73ef329701b4c1ca4c2fb716f90a881a8f34ef1ff855c8fd422b83e0`).
- `Gradle Wrapper Validation` run `43710533945`: SUCCESS.
- Brak potwierdzenia `assembleDebug`/APK w tych przebiegach. Nie oznaczać APK jako zbudowanego.


## Potwierdzenie CI na aktualnym HEAD — 2026-11-15, 13:xx UTC
- HEAD PR #3: `ff2a00a934eb5e3f7edeb369b1e96a1cf9ce9906`.
- `AiFORTeca Unit Tests`, run `43711354234`: SUCCESS; job `unit-tests`, krok `Run unit tests` zakończony powodzeniem, raporty przesłane.
- Artefakt raportów: `aiforteca-unit-test-reports`, ID `14161095145`, 341423 bajty.
- `Gradle Wrapper Validation`, run `43711354221`: SUCCESS.
- Ten przebieg potwierdza testy jednostkowe i walidację wrappera dla całego HEAD. Nie potwierdza `assembleDebug`, `assembleRelease`, metadanych APK ani testu na urządzeniu.
- PR #3 nadal OPEN/DRAFT i mergeable. Nie wykonano merge.

## Następny zakres — pakiet 3 (jeszcze nierozpoczęty)
- Kierunek: ergonomia mobilnego centrum MASTER / układ obszaru roboczego; najpierw audyt aktualnego układu i istniejących komponentów, bez dublowania już wdrożonych funkcji kolejki i komunikacji.
- Limit: dokładnie cztery poprawki kodu, następnie pełne testy CI. Nie oznaczać pakietu jako ukończonego przed potwierdzeniem wyników.

## Potwierdzenie kontrolnego CI przed pakietem 3 — 2026-11-15
- Commit kontrolny: `4178cce4c656a8e38a445410edbace6a0434d845`.
- `AiFORTeca Unit Tests`, run `43712092815`: SUCCESS; job `unit-tests`, kroki testów i uploadu raportów zakończone powodzeniem.
- `Gradle Wrapper Validation`, run `43712092869`: SUCCESS.
- Pakiet 2 pozostaje zaliczony; powtórny przebieg sprawdził także aktualizację rejestru. APK nie było częścią tego przebiegu.

## Pakiet 3 — centrum MASTER / mobilny workspace
- Baza: `4178cce4c656a8e38a445410edbace6a0434d845`.
- Zmiany kodu (4 zakresy):
  1. Nawigacja działów: zaznaczenie aktywnej zakładki, opis dostępności i automatyczne przewinięcie paska do wybranego działu.
  2. Centrum MASTER: kompaktowy, poziomy pasek skrótów do zespołu, zadań, komunikacji, modeli, diagnostyki i czatu zamiast długiej kolumny przycisków.
  3. Konsola MASTER: widok pięciu ostatnich zdarzeń operacyjnych z przejściem do pełnej diagnostyki; bez treści promptów i kluczy API.
  4. Duże ekrany: ograniczenie szerokości workspace do maksymalnie 960 dp i wyśrodkowanie; szerokość jest dodatkowo ograniczona do dostępnego obszaru, aby uniknąć poziomego obcięcia.
- Commity zmian UI: `354eb69b2d25a5dbf3152749be070c91660607c4`, `9ca5c3ffcc4e1a3037dcc8b9a8d6e5f85fff1800`, `fb7d84eb43347c255f6f9471ea3fbb580b470d4a`, `71715ff141475af6c721c501e59aa93688dccfa3`; korekta ograniczenia szerokości: `dc4f40238ac3da1fa129977c74217ae3272d2fdb`.
- Aktualny HEAD: `dc4f40238ac3da1fa129977c74217ae3272d2fdb`.
- Status: kod pakietu 3 zapisany; pełne testy CI i build Android oczekują na potwierdzenie. Nie oznaczać pakietu jako zaliczonego przed zielonym CI.
- APK: brak nowego, potwierdzonego artefaktu. Nie blokuje dalszych prac, ale nie jest uznawane za zbudowane.


## Potwierdzenie pakietu 3 — 2026-11-15
- HEAD kodu i testów: `a2325600d060ab1deec865c63b98c027bdf126e4`.
- `AiFORTeca Unit Tests`, run `43712612728`: SUCCESS; job `unit-tests`, kroki testów i uploadu raportów zakończone powodzeniem.
- Artefakt raportów: `aiforteca-unit-test-reports`, ID `14161437051`, 341384 bajty, SHA-256 `893a53d18084720f3a75f07f5f9b7ebee610639627104dfb3628ebc55c169dfa`.
- `Gradle Wrapper Validation`, run `43712612796`: SUCCESS.
- Pakiet 3 zaliczony w zakresie testów jednostkowych i walidacji wrappera. Nie potwierdzono `assembleDebug`, `assembleRelease`, metadanych APK ani testu na urządzeniu.
- PR #3 pozostaje OPEN i mergeable; bez merge.

## Pakiet 4 — planowany zakres
- Start dopiero po zielonym CI pakietu 3 (spełniono).
- Priorytet: kontrolowane mutacje pamięci i konfiguracji agentów — zakres uprawnień, wymagane zatwierdzenie operacji wrażliwych, historia zmian i bezpieczne zachowanie przy błędach odczytu/zapisu.
- Przed zmianami kodu wymagany audyt istniejących store'ów i testów, aby nie dublować mechanizmów już obecnych.
- Limit: cztery poprawki kodu, następnie pełne CI; bez oznaczania pakietu jako zaliczonego przed potwierdzeniem.


## Rejestr uzupełniający — pakiety 21–22 (2026-11-20)

Pełny opis pakietów 15–21 znajduje się w `docs/AIFORTECA_0.2_FOUNDATION_PLAN.md` i w checkpointach wskazanych w `AGENT_HANDOFF_PROMPT_PL.md`. Poniższe wpisy uzupełniają starszy rejestr, który nie został historycznie zsynchronizowany z późniejszymi pakietami.

### Pakiet 21 — niezawodny kontekst rozmowy
- Baza: `a46d33512cd9bfd5c93406f59c93a94e96b35a56`; końcowy kod: `fb8d4b6d10526f1107a164827ae6795069d10351`; commit kontrolny Android build: `7e7ae1e025eb17c7cb9753a30f18566e3fa343f5`.
- Zakres: bounded conversation history, provider-specific payload mapping, exact chat snapshot dla request worker i regresje provider payload/history isolation.
- CI: Unit Tests SUCCESS, 1 342 testy / 0 failures / 0 errors / 0 skipped; Gradle Wrapper Validation SUCCESS. Android Build #758 SUCCESS; debug i signed release APK opublikowane. Raporty i artefakty: run ID `44554818564`, artefakty `14546770962` i `14544344776`.
- Status: pakiet 21 zamknięty; testy urządzenia i ręczna walidacja historii na telefonie pozostają osobne.

### Pakiet 22 — konsola diagnostyczna na żywo
- Baza: `7e7ae1e025eb17c7cb9753a30f18566e3fa343f5`.
- Zakres czterech zmian: filtr źródła zdarzeń; widoczne filtry dziennika; odświeżanie listy in-place co 5 s; zatrzymywanie/wznawianie odświeżania zgodnie z lifecycle Activity i zmianą działu.
- Test: dodano regresję filtra `QUEUE`/`HANDOFF`; istniejące testy redakcji payloadów pozostają.
- Status: implementacja przygotowana, CI PENDING. Lokalny Gradle 9.9.0 nie uruchomił się, bo `services.gradle.org` nie rozwiązał DNS (`UnknownHostException`). Nie oznaczać pakietu jako PASS przed zielonym CI na finalnym HEAD.
- Release identity bez zmian: `0.2` / `versionCode 3`.


### Aktualizacja pakietu 22 — wyniki CI i testów użytkownika (2026-11-20)
- Po commicie kodu pakietu `f3030e516722b40a63f19bf553f55861e48f7861` oraz triggerze `0f625db363fb131c9004bfef3a67c258bcf3c58d`, oba przebiegi Unit Tests zakończyły się FAILURE: PUSH run `44809127357`, PR run `44809137058`.
- Przyczyna: w `MainActivity.showDiagnostics()` użyto `queueDiagnostics` i `handoffDiagnostics` bez lokalnego pobrania snapshotów; kompilator zgłosił niezdefiniowane referencje i błędy wtórne. Raportów testowych nie utworzono; brak artefaktów.
- Poprawka: commit `3c16997226956e3bd70822a27592dc6526d974c0` dodaje odczyt `MasterTaskQueueStore.snapshot(this)` i `AgentHandoffStore.snapshot(this)` w zakresie funkcji. Oczekuje na ponowne pełne CI; brak potwierdzenia PASS i APK.
- Testy ręczne użytkownika APK 0.2: PASS — pamięć w bieżącym czacie, pamięć między nowymi czatami, kolejka MASTER, diagnostyka/dziennik, powrót po przełączeniu aplikacji. Nie zaliczone/do ponownego sprawdzenia — komunikacja agentów, odzyskiwanie zadania po restarcie.
- Zgłoszone defekty do kolejnego zakresu: (1) poprawić usuwanie modeli i spójne czyszczenie przypisań; (2) umożliwić agentom wybór modeli podstawowych z puli modeli głównych już skonfigurowanych w aplikacji.
- Pakiet 22 pozostaje OTWARTY do czasu zielonego CI na finalnym HEAD, weryfikacji testów i zbudowania APK. Pakiet 23 nie rozpoczęty.

- Dodatkowa poprawka konfiguracji CI: commit `79224bb23862729bd77aa650f74d448f37a89d00` aktualizuje Foojay resolver convention z `0.10.0` do `1.0.0`. Log run `44829203555` wykazał, że po naprawie błędów Kotlin build zatrzymuje się podczas rozwiązywania pluginu w `settings.gradle.kts`; plugin 0.10.0 poprzedza obsługę Gradle 9, a 1.0.0 jest wersją wymaganą dla Gradle 9/Java 17. Zmiana oczekuje na potwierdzenie CI.

---

# Uzgodnienie rejestru — pakiety 23–27 (stan sprawdzony 2026-11-22)

Ten blok jest aktualnym uzupełnieniem rejestru. Starszy wpis z 2026-11-20, w którym pakiet 22 i pakiet 23 były oznaczone jako oczekujące/nie rozpoczęte, jest historycznym stanem sprzed późniejszych prac. Nie należy go traktować jako aktualnego statusu gałęzi. Weryfikacja dotyczy PR #3 i jego gałęzi `work/aiforteca-0.2-master-agent-foundation`.

## Pakiet 23 — kontrola gotowości agentów przed uruchomieniem

- Końcowy commit kodu/builda: `1fa79f0c008f394a676b1e8045f149f751453e3c`.
- Zakres: metadane agentów na liście; czytelne stany modeli (bezpłatny/nieznany/niegotowy); blokada startu przed wysłaniem zapytań, gdy przypisany model nie jest gotowy; diagnostyka wskazująca konkretnych agentów i powód blokady. Zachowano istniejące przypisania agentów/modeli oraz ochronę kosztową.
- Weryfikacja: pełny Android Build SUCCESS; 1 348 testów, 0 failures, 0 errors, 0 skipped. Debug i release APK zostały opublikowane jako artefakty.
- Debug APK: 2 193 205 B; SHA-256 `6386fe4051b6d5e7aca223c9bdc79f32bc06954bdc9ac2203b7770faae78b827`.
- Ograniczenie: certyfikat APK nie został niezależnie zweryfikowany narzędziem `apksigner`; nie deklarować takiej weryfikacji.
- Status: CI/build/testy PASS; testy fizycznego urządzenia pozostają odrębne.

## Pakiet 24 — pełny kontekst diagnostyczny pojedynczego zadania

- Końcowy commit kontrolny CI/build: `2b91c79420b84d226042269a7128cd818ddc00f2`.
- Wcześniejszy commit implementacyjny funkcji raportu: `92660990e181373ae61dccba49d876595370c0ce`.
- Zakres: jeden raport obejmujący całe zadanie i konkretny run; pełne raporty agentów; zdarzenia powiązane z runem; zawartość przekazania HANDOFF; przycisk kopiowania pełnego kontekstu; jawne oznaczanie brakujących źródeł/danych; redakcja sekretów; brak fałszywego statusu sukcesu przy niekompletnych danych.
- Weryfikacja: pełny Android Build i CI PASS; 1 351 testów, 0 failures, 0 errors, 0 skipped. Artefakty debug APK, signed release APK, source ZIP i raporty testów zostały opublikowane i zweryfikowane.
- Debug APK: 2 201 993 B; SHA-256 `97300d513087110745bce4423ab63206ab1efc2faecc259495a1949fff4da574`.
- Signed release APK: 1 476 742 B; SHA-256 `943f234d998aa460c20f12a95c534cf72f5b3e4999f564fcac10a31b1d6c33a9`.
- Status: CI/build/artefakty PASS. Testy urządzenia pozostają osobne.

## Pakiet 25 — bezpieczne odzyskiwanie przerwanego wykonania

- Baza: stan po pakiecie 24.
- Commity kodu: `1040ec6eb43c0a4c117415ba78ba2d1b256f34ba`, `4e1ee15403bc44495140809156c363ed64ee3784`, `6485c15c29bd862b209bc826d28c6aa12fec42cc`, `21ad8bd921d6d3558458cfff7364ceb239ee32fd`, `af3e4245ce3fcfe58c6d6f4cdb0c0266ff9ae06f`, `247addab560223eab2c5307057420a77d9819221`.
- Commit kontrolny Android Build: `a1930da0d15154689fc24b5d70f5d65f28d3fa6b`.
- Zakres: wznowienie tylko po sprawdzeniu checkpointów i rozliczenia użycia; zachowanie ukończonych raportów; blokada wznowienia przy niepewnym/nieuzgodnionym zużyciu; nowy runId/epoch odcinający spóźnione callbacki; wznowienie wyłącznie pozostałych agentów.
- Weryfikacja: Unit Tests, Gradle Wrapper Validation i Android Build SUCCESS. 1 360 testów, 0 failures, 0 errors, 0 skipped. Artefakty debug APK, signed release APK, source ZIP i raporty testów opublikowane i zweryfikowane. Android Build run `44921097696`.
- Status: CI/build/artefakty PASS. Fizyczny test odzyskiwania po ubiciu procesu aplikacji nadal PENDING — nie oznaczać go jako zaliczonego.

## Pakiet 26 — atomowe przygotowanie archiwum przed wznowieniem

- Baza: pakiet 25.
- Commit kodu: `d0b78cd0c720fd0da4b10a7298b6011c18f0f7fb`.
- Commit kontrolny Android Build: `6feaae94638de5a0b70d2b7884cd22d6e9d99784`.
- Zakres: idempotentne staging kopii ukończonych raportów do nowej epoki przed zmianą runId; walidacja wszystkich źródeł i istniejących celów przed zapisem; brak nadpisywania konfliktujących danych; bezpieczne ponowienie po przerwaniu procesu; zwolnienie rezerwacji budżetu i blokada wysyłania zapytań przy błędzie stagingu.
- Testy regresyjne: staging wszystkich raportów przed zmianą epoki; brak źródła bez częściowych zapisów; idempotencja identycznego celu; blokada konfliktu bez nadpisania.
- Weryfikacja: Unit Tests, Gradle Wrapper Validation i Android Build SUCCESS. 1 364 testy, 0 failures, 0 errors, 0 skipped. Android Build run `44923001624`; raporty i artefakty debug/release/source zostały opublikowane i zweryfikowane.
- Status: CI/build/artefakty PASS. Fizyczny test odzyskiwania po restarcie/ubiciu procesu nadal PENDING.

## Pakiet 27 — pochodzenie mutacji pamięci w audycie

- Baza: stan po pakiecie 26.
- Commit kodu: `79eea4084105dd1591965a87397856baa3919157`.
- Commit kontrolny Android Build / aktualny HEAD PR: `012254d0fbceab847de9d85fbbb7725135486211`.
- Zakres: `sourceRef` w żądaniu zarządzanej mutacji; walidacja długości i redakcja potencjalnych sekretów; zapis źródła w append-only MemoryAuditLog i objęcie go łańcuchem hashy; odczyt starszych rekordów bez pola sourceRef z zachowaniem zgodności starego hasha; propagacja źródła przez MemoryMutationGate i oznaczone źródła wywołań UI.
- Testy: trwałość sourceRef i ochrona hashem przed manipulacją; zgodność starych rekordów po dopisaniu nowych; walidacja sourceRef; propagacja do zdarzeń audytowych.
- Weryfikacja na HEAD `012254d0...`: Unit Tests PUSH run `44926350673` SUCCESS; PR run `44926354726` SUCCESS; Gradle Wrapper Validation PUSH run `44926350688` SUCCESS; PR run `44926354675` SUCCESS; Android Build run `44926350753` SUCCESS.
- Wynik testów: 1 368 testów, 0 failures, 0 errors, 0 skipped (raport testów pakietu 27 zweryfikowany dla kodu `79eea408...`; pełny Android Build na HEAD `012254d0...` ponownie wykonał testy i opublikował raport).
- Artefakty Android Build run `44926350753` — wszystkie nieprzeterminowane i powiązane z dokładnym HEAD `012254d0...`:
  - `aiforteca-debug-apk`, ID `14716496011`, 2 202 037 B, SHA-256 `c77dc7f8c5bee7d6a69ad01ed79258fd4ae2c14e9a79c4113c4bf2fea742f688`.
  - `aiforteca-release-apk-signed`, ID `14716635414`, 1 473 856 B, SHA-256 `f90b8a8798f0e5af363e3171bcd6fc1d4a262a761534f31f9ad4268d6c39e54d`.
  - `aiforteca-source-project`, ID `14716496007`, 1 548 250 B, SHA-256 `96c1e7d376c0153e1ae35014d0d5e96fd02596cde00256f32fe07f8a74b1b267`.
  - `aiforteca-unit-test-reports`, ID `14716365785`, 357 788 B, SHA-256 `67615940edbdc54d0e2bb884ce90f22ce507a3dc278e21618a45dabb0b451972`.
- Status: CI/build/testy/artefakty PASS. Nie pobierano APK do tej rozmowy, ponieważ nie jest potrzebne do dalszych prac. Test fizycznego urządzenia pozostaje odrębny.

## Stan GitHub po uzgodnieniu — 2026-11-22

- Repozytorium: `krzysztofchojnacki5-ship-it/aimaster_071_work`.
- PR #3: OPEN, DRAFT, nie scalony; baza `work/aiforteca-0.0.2-provider-connectivity`; gałąź `work/aiforteca-0.2-master-agent-foundation`.
- HEAD kodu pakietu 27, na którym wykonano pełny Android Build: `012254d0fbceab847de9d85fbbb7725135486211`.
- Następny commit `48b98c340df61b96004975bb11b9072455b28741` zawiera wyłącznie uzgodnienie tego rejestru; nie zmienia kodu aplikacji. Dla tego commitu `Gradle Wrapper Validation` zakończył się SUCCESS (run `44962585678`).
- Porównanie gałęzi po aktualizacji rejestru: 651 commitów do przodu, 0 commitów za bazą; 140 plików różnicy (17 730 additions / 1 028 deletions).
- Wszystkie wymagane workflowy na kodowym HEAD `012254d0...` zakończone SUCCESS: Unit Tests PUSH/PR, Gradle Wrapper Validation PUSH/PR oraz pełny Android Build PUSH. Po aktualizacji samej dokumentacji dodatkowo potwierdzono Wrapper Validation SUCCESS na `48b98c34...`.
- Android Build wykonał testy, assemble release APK, spakował projekt źródłowy i przesłał debug APK, signed release APK, source ZIP oraz raporty testów.
- Dawne anulowane/nieudane przebiegi na wcześniejszych SHA są historią CI i nie są wynikiem dla aktualnego HEAD. Nie usuwano ich.
- PR pozostaje Draft; nie wykonywać merge bez wyraźnej decyzji użytkownika.
- Testy ręczne: komunikacja HANDOFF potwierdzona przez użytkownika jako PASS (kod kontrolny przeniesiony między agentami bez regenerowania); fizyczny test odzyskiwania po restarcie/ubiciu procesu nadal PENDING.


## Pakiet 28 — uczenie jakości routingu (wynik końcowy, 2026-11-22)

- Baza: `409ba6097e4182b958cb511c4c2c7d707ad3df3c`.
- Commity implementacyjne i korekcyjne: `931992d92a23abf0716581afca2ba3ad4100b42a`, `1681d50d7da50b78fdb7741eecc80278595cc1c6`, końcowy testowy HEAD `4915b0ff5358800220c04d458b40e6e45401cea3`.
- Zakres: uwzględnienie ocen CHAT_ANSWER w danych jakości routingu; normalizacja identyfikatorów agentów; odrzucanie nieprawidłowych i niepowiązanych ocen; deduplikacja prób na podstawie taskId.
- Pierwszy CI na wcześniejszym commicie `81633e78...` wykazał 3 błędy testów; poprawiono filtr w `qualityScore` i dodano test regresyjny. Nie zaliczano anulowanych runów pośrednich.
- Końcowe Unit Tests: PUSH run `45028549989` (#661) SUCCESS i PR run `45028553806` (#662) SUCCESS, oba dla HEAD `4915b0f...`.
- Gradle Wrapper Validation: PUSH `45028549970` (#1409) SUCCESS i PR `45028553847` (#1410) SUCCESS.
- Zweryfikowane raporty XML: w każdym archiwum 128 plików XML, 1 372 testy, 0 failures, 0 errors, 0 skipped.
- Artefakty raportów: PUSH `14756454521`; PR `14756897605`; oba nieprzeterminowane i przypięte do właściwego HEAD.
- Android Build/APK: NOT REQUESTED / NOT RUN. Test urządzenia: NOT RUN.
- Status pakietu 28: CI + testy + wrapper PASS; brak Android APK nie blokuje dalszej pracy.

## Pakiet 29 — oddzielne uczenie jakości peer-review (2026-11-22)

- Baza: końcowy HEAD pakietu 28 `4915b0ff5358800220c04d458b40e6e45401cea3`.
- Commit funkcjonalny: `a2841e5b7a7c1894d36bb7e648f0f348999b26eb`.
- Końcowy kodowy HEAD po korekcie składni raportu MASTER-a: `415a0f314fa0dfcfa6ef0b5b0dfa00efbf8465ed`.
- Cztery poprawki:
  1. Dziennik wykonania przechowuje bezpieczne, zgodne wstecznie pole `reviewedAgentId`, dzięki czemu zachowuje relację recenzent → oceniany raport.
  2. Istniejący ekran 👍/👎 udostępnia ukończone peer-review do jawnej oceny użytkownika i zapisuje je jako `REVIEW_ANSWER`.
  3. Dodano oddzielną projekcję dowodów i scorer jakości recenzenta; `REVIEW_ANSWER` nie jest mieszany z `AGENT` ani `CHAT_ANSWER` i nie zmienia automatycznie routingu.
  4. Analiza jakości MASTER-a raportuje oceny recenzentów osobno od ocen odpowiedzi; dodano testy rozdzielenia sygnałów i zgodności starego formatu dziennika.
- Unit Tests: PUSH run `45030941990` (#665) SUCCESS i PR run `45030945423` (#666) SUCCESS, oba dla HEAD `415a0f3...`.
- Gradle Wrapper Validation: PUSH `45030941987` (#1413) SUCCESS i PR `45030945406` (#1414) SUCCESS.
- Zweryfikowane raporty XML: w każdym archiwum 128 plików XML, 1 374 testy, 0 failures, 0 errors, 0 skipped.
- Artefakty raportów: PUSH `14758245053` (359 243 B, SHA-256 `85982ea58c838c297ee1533446f5a25b398c37865eea8afa6d12d5ef863ba065`); PR `14757628512` (358 947 B, SHA-256 `397904e0f2328569c2852b6af774b25f235fc3ddd87cee19133ee6a70c90f001`). Oba nieprzeterminowane i przypięte do HEAD `415a0f3...`.
- Android Build/APK: NOT REQUESTED / NOT RUN. Test urządzenia: NOT RUN.
- Status pakietu 29: CI + testy + wrapper PASS. Nie uruchamiano Android Build i nie deklaruje się APK.

## Stan GitHub po pakiecie 29 — 2026-11-22

- Repozytorium: `krzysztofchojnacki5-ship-it/aimaster_071_work`.
- PR #3: OPEN, DRAFT, nie scalony; baza `work/aiforteca-0.0.2-provider-connectivity`; gałąź `work/aiforteca-0.2-master-agent-foundation`.
- Kodowy HEAD pakietu 29: `415a0f314fa0dfcfa6ef0b5b0dfa00efbf8465ed`.
- Pakiety 28 i 29 mają po cztery poprawki; nie zwiększono rozmiaru pakietu.
- Nie zmieniano base branch, metadanych PR, wersji aplikacji ani konfiguracji dostawców.
- Nie tworzono APK. Następny pakiet może rozpocząć się dopiero po zapisaniu tego checkpointu i potwierdzeniu aktualnego HEAD/CI.


## Pakiet 30 — ranking recenzentów na podstawie ocen użytkownika (2026-11-22)

- Baza: końcowy, udokumentowany stan po pakiecie 29 `798d051c2bc338bde95f30488ec497ae606f67bd`.
- Commit kodu: `33052e2c0099d175d0480e13b3b14f60b2c9b5cd`.
- Pięć poprawek:
  1. `PeerReviewPlanner` porządkuje kandydatów do recenzji według odrębnego wyniku jakości peer-review; wynik null/niepoprawny jest neutralny, a kolejność wejściowa pozostaje stabilnym tie-breakerem.
  2. `PatchPeerReviewPlanner` stosuje ten sam ranking, ale nadal wymaga unikalnych agentów i różnych fingerprintów provider/model.
  3. Oba przepływy produkcyjne w MASTER przekazują scorer oparty wyłącznie na ocenach `REVIEW_ANSWER`; próg domyślny to 5 niezależnych ocen.
  4. Analiza jakości MASTER-a pokazuje wygładzony wynik recenzenta dopiero po osiągnięciu minimalnej liczby próbek; wynik nie miesza się z jakością zwykłych odpowiedzi.
  5. Testy regresyjne sprawdzają priorytetyzację, stabilny fallback przy braku/niepoprawnym wyniku, brak self-review i zachowanie różnorodności providerów.
- Unit Tests: PUSH run `45032331107` (#669) SUCCESS i PR run `45032337589` (#670) SUCCESS, oba dla HEAD `33052e2...`.
- Gradle Wrapper Validation: PUSH `45032331092` (#1417) SUCCESS i PR `45032337625` (#1418) SUCCESS.
- Zweryfikowane raporty XML: w każdym archiwum 128 plików XML, 1 379 testów, 0 failures, 0 errors, 0 skipped.
- Artefakty raportów: PUSH `14758202935` (358 955 B, SHA-256 `70b72990069f1046df65ccc8142267e194a50793fe6f1155c6efa59987941d8a`); PR `14758721602` (359 236 B, SHA-256 `1edd72d3659008be9d3de560dc5ff14d9fef1eef2612756cc0d0671f2d301d69`). Oba nieprzeterminowane i przypięte do HEAD `33052e2...`.
- Android Build/APK: NOT REQUESTED / NOT RUN. Test urządzenia: NOT RUN.
- Status pakietu 30: CI + testy + wrapper PASS; pięć poprawek w pakiecie zgodnie z regułą po dwóch kolejnych zaliczeniach.

## Stan GitHub po pakiecie 30 — 2026-11-22

- Repozytorium: `krzysztofchojnacki5-ship-it/aimaster_071_work`.
- PR #3: OPEN, DRAFT, nie scalony; baza `work/aiforteca-0.0.2-provider-connectivity`; gałąź `work/aiforteca-0.2-master-agent-foundation`.
- Kodowy HEAD pakietu 30: `33052e2c0099d175d0480e13b3b14f60b2c9b5cd`.
- Pakiety 28, 29 i 30 mają potwierdzone zielone testy PUSH/PR; rozmiar pakietu 30 wynosi 5 zgodnie z regułą dwóch kolejnych zaliczeń.
- Nie zmieniano base branch, metadanych PR, wersji aplikacji ani konfiguracji dostawców.
- Nie tworzono APK ani nie uruchamiano Android Build.
- Pakiet 31 pozostaje na poziomie maksymalnie 5 poprawek; przed implementacją wykonać ponowną kontrolę repo, branch, HEAD, PR i aktywnych runów.

# AiFORTeca 0.2 — checkpoint pakietu 31: konserwatywny ranking i specjalizacja recenzentów

Data: 2026-11-23

- Repozytorium: `krzysztofchojnacki5-ship-it/aimaster_071_work`
- Branch: `work/aiforteca-0.2-master-agent-foundation`
- PR: #3 OPEN/DRAFT; bez zmian metadanych PR.
- Baza pakietu: `33052e2c0099d175d0480e13b3b14f60b2c9b5cd`.
- Końcowy kodowy HEAD: `8ba2ee4ba9fc43814568db879d566cce694a91f4`.

## Pięć poprawek

1. Dodano konserwatywny wynik jakości recenzenta oparty na dolnej granicy ufności, aby mała próba ocen nie dawała nadmiernej przewagi.
2. Raportowy Peer Review i Patch Peer Review wykorzystują ranking oparty na konserwatywnym wyniku, zachowując stabilny porządek przy braku wiarygodnych danych.
3. Patch Peer Review uwzględnia kategorię zadania/ścieżkę pliku (np. `patch:kt`) przy ocenie specjalizacji recenzenta; nadal wymaga różnych agentów i niezależnych fingerprintów provider/model.
4. Diagnostyka pokazuje liczbę niezależnych próbek i jawnie sygnalizuje dane poniżej progu, zamiast przedstawiać niewystarczającą próbę jako pełny wynik.
5. Naprawiono ograniczenie liczby recenzentów: pętla selekcji kończy się po osiągnięciu limitu; wcześniejsze `return@forEach` pomijało tylko bieżący element i mogło dopuścić nadmiarowe wywołanie.

## Istotne commity

- `204e57175f884f39e88030bddad0900d87172fae` — wynik konserwatywnej jakości.
- `fb0d24659aef5a534afd28ec6f84cebf9f24b051` — ranking po dolnej granicy ufności.
- `3ea484ad2356e3d75f71944c1eb3ea4f5e116105`, `32b872b68959f479e5676e916cf5de590d92a653` — diagnostyka liczby próbek.
- `40620922ae8785577442413131a4d02b5fa54273` — ranking kategorii zadania/ścieżki.
- `8ba2ee4ba9fc43814568db879d566cce694a91f4` — egzekwowanie limitu recenzentów.
- Dodatkowe commity testowe i korekcyjne: `b937e12fa9ce35ece2955385041549682d6603ef`, `6ff919dfcc7033134146ce1dc8636f3b26dfa459`, `a7df62d994bd369e4a56098e3b4bf80c4865457a`, `325688b398f1fa6834f566089bfff88a753b7303`.

## Weryfikacja na dokładnym HEAD

- AiFORTeca Unit Tests PR run #692 (`45240665592`): SUCCESS.
- Gradle Wrapper Validation PR run #1440 (`45240665595`): SUCCESS.
- Raport XML z artefaktu `aiforteca-unit-test-reports`, ID `14851836450`: 128 plików XML, 1 384 testy, 0 failures, 0 errors, 0 skipped.
- Krok `Run unit tests` i publikacja raportów zakończone SUCCESS.
- Android Build/APK: NOT RUN w tym pakiecie. Artefakt powyżej to ZIP raportów XML, nie APK.
- Test fizycznego urządzenia: NOT RUN.

Status: pakiet 31 zamknięty dla testów jednostkowych i walidacji Gradle Wrapper. Nie deklarować APK ani testów urządzenia.

## Następny etap

Pakiet 32: wrócić do głównego celu produktu — rzeczywistego sterowania kolejką MASTER i wykonaniem agentów. Przed zmianami sprawdzić ponownie aktualny HEAD, PR i workflow; wybrać dokładnie pięć poprawek lub mniej, bez osłabiania Smart Budget, rozliczania usage, checkpointów, anulowania i odzyskiwania.


## Pakiet 32 — bezpieczna finalizacja wyników kolejki MASTER (2026-11-23)
- Baza: `103f1ee0461422ac59c10a872723b005494828c3`; końcowy HEAD kodu: `16f19aa84ce028ead70541ea094efdca51bdfc2a`.
- Pięć zabezpieczeń: fail-closed finalizer wyników TeamRunEngine; zatrzymanie przed wywołaniami API przy nieudanym zapisie decyzji Smart Budget; odrzucanie duplikatów i obcych agentów; jawny FAILED dla brakującego wyniku; BLOCKED przy niekompletnej finalizacji. Obsługa błędu ograniczona do dokładnego task/run ID.
- Dodano `QueueExecutionResultFinalizerTest` — 6 testów, w tym pusty zestaw agentów, obce/duplikowane wyniki, brakujące wyniki, błąd persystencji i kolejność zapisu.
- `AiFORTeca Unit Tests` PR run #696 (`45253483098`) i push run #697 (`45253494751`) — SUCCESS. Gradle Wrapper Validation #1444 (`45253483108`) i #1445 (`45253494752`) — SUCCESS.
- Raport XML z artefaktu `aiforteca-unit-test-reports`, ID `14857134759`: 129 plików XML, 1 390 testów, 0 failures, 0 errors, 0 skipped. Nowy suite `QueueExecutionResultFinalizerTest`: 6/6, bez błędów.
- Workflow jednostkowy wykonuje `./gradlew --no-daemon testDebugUnitTest`; nie wykonuje `assembleDebug` ani `assembleRelease`. Android Build workflow nie uruchomił się na tym HEAD, ponieważ jest ograniczony ścieżką triggera `.github/ci/full-android-build.trigger`. APK nie jest potwierdzone i nie jest deklarowane jako zbudowane.
- PR #3 nadal OPEN/DRAFT; bez merge. Zgodnie z wcześniejszą decyzją użytkownika prace funkcjonalne nie czekają na APK; status builda/APK pozostaje jawnie oddzielony.
- Pakiet 32 zamknięty w zakresie testów jednostkowych i walidacji wrappera. Następny kierunek: audyt i rozbudowa rzeczywistego strumienia komunikacji agent-agent (nadawca/odbiorca/zadanie/provider/model/status/payload redacted), bez dublowania istniejącego AgentHandoffStore.


## Pakiet 33 — jednolita oś komunikacji agentów (2026-11-24)

- Baza: pakiet 32, HEAD kontrolny `16f19aa84ce028ead70541ea094efdca51bdfc2a`.
- Końcowy kodowy HEAD: `db354e6ab491027aae6f14b291de6fd26756b804`.
- Zakres czterech zmian:
  1. Dodano projekcję `QueueCommunicationTimeline`, łączącą zdarzenia kolejki MASTER z trwałymi przekazaniami `AgentHandoffStore`.
  2. Włączono rzeczywiste payloady przekazań i ich stany do osi; przy niedostępnym magazynie przekazań UI pokazuje ostrzeżenie i nie prezentuje niezweryfikowanych raportów.
  3. Powiązano metadane provider/model z dokładnym taskId, runId, nadawcą i czasem zdarzenia; dodano mapowanie identyfikatorów agentów na nazwy, także dla agentów niestandardowych/wyłączonych.
  4. Dodano odświeżanie osi co 3 sekundy wyłącznie podczas wyświetlania ekranu Rozmowy, z zatrzymywaniem w lifecycle Activity i zachowaniem pozycji przewijania.
- Commity: `4440d4e3e72b306dc111ca1554a74295069d2298`, `8aed9a9acb6aff4ad838638291fb2c9e69d7093b`, `a95b912a897a2c2b85814ce9c0893a5a3099b18a`, `0e9f4bbdf7e96f93d03918aaa7265f71092d10e5`; korekta kompilacji zachowania pozycji przewijania: `db354e6ab491027aae6f14b291de6fd26756b804`.
- Unit Tests PR run #708 (`45277535826`) na dokładnym HEAD `db354e6...`: SUCCESS. Raport XML z artefaktu `aiforteca-unit-test-reports`, ID `14867916988`: 130 zestawów, 1 396 testów, 0 failures, 0 errors, 0 skipped.
- Gradle Wrapper Validation run #1456 (`45277535785`) na tym samym HEAD: SUCCESS.
- Android Build/APK: NOT RUN / NOT REQUESTED. Test urządzenia: NOT RUN.
- PR #3 pozostaje OPEN/DRAFT; bez merge. Pakiet 33 zamknięty w zakresie testów jednostkowych i walidacji wrappera.


## Etap 34 — uzgadnianie trwałych raportów i stanu kolejki MASTER (2026-11-26, w toku)

- Baza: HEAD po odzyskiwaniu ASSIGNED/RUNNING `b4b8aa61a2c49be780d10e3479f272e9939d6db1`.
- Repo: `krzysztofchojnacki5-ship-it/aimaster_071_work`; branch: `work/aiforteca-0.2-master-agent-foundation`; PR #3 OPEN/DRAFT, mergeable.
- Aktualny HEAD etapu: `e917d8e55489b48e8ea3393d8111a4e391e13d2b`.

### Zmiany kodu

1. Dodano czystą politykę `MasterTaskReconciliationPolicy`, która nie wykonuje side-effectów i podejmuje decyzje wyłącznie na podstawie dostarczonych dowodów z trwałych magazynów.
2. Dodano `MasterTaskReconciliationReader`, który odczytuje snapshot kolejki, archiwum raportów i zdarzenia `AGENT_RESULT`; nie wznawia zadań, nie naprawia automatycznie stanu i nie wysyła ponownie żądań API.
3. Polityka rozróżnia: niedostępny/uszkodzony magazyn kolejki, brak zadania, nieczytelne archiwum, niepoprawną epokę, brak raportów, raport bez zdarzenia wyniku, zdarzenie wyniku bez raportu oraz niezgodność statusu terminalnego z wynikami agentów.
4. Gdy wszystkie raporty i zdarzenia są zgodne dla bieżącego `runId`, polityka wyznacza status końcowy na podstawie trwale zapisanych wyników: FAILED ma pierwszeństwo przed BLOCKED, a BLOCKED przed COMPLETED. Dla RUNNING zwraca `READY_TO_FINALIZE`; nie zmienia samodzielnie stanu kolejki.
5. Dodano testy pure policy obejmujące brakujące/niezgodne dane, epoki, niedostępne archiwum, nieobsługiwane statusy wyników, agregację FAILED/BLOCKED i konflikt statusu kolejki z raportami.

### Commity

- `1b71bc5598783c8696604e8a99fd65d936f05cf0` — polityka uzgadniania.
- `eada43cb58d08d365a297d95f79e74dce866a30b` — testy polityki.
- `ac661ce3eb51a1199e81bb352d0cedbf735fee95` — korekta wykrywania osieroconych zdarzeń.
- `f7eddfa5f3d28e606d792b0f02b4d09a80d85170` — czytnik trwałych dowodów.
- `800b312e166c6a6252b4416b4b4d5e5c2ec5624b` — rozróżnienie brakującego raportu i osieroconego eventu.
- `15a359054e515d909de31f83f229d01c2ef69b26` — agregacja statusu z zapisanych wyników.
- `d2fcb005fd4bfdbfcf451de73446527b41279613` — testy agregacji statusu.
- `fdec23e1d882e2928fea6c84b97a4d779594c8aa` — poprawione fixture testowe.
- `ce23ddcba2453551e91eceeb5e0a066abf5055bc` — kontrola zgodności statusu terminalnego.
- `e917d8e55489b48e8ea3393d8111a4e391e13d2b` — test niezgodności terminalnego statusu kolejki z wynikami agentów.

### Weryfikacja

- GitHub PR #3 wskazuje HEAD `e917d8e...`, branch i base zgodne z powyższym checkpointem; PR OPEN/DRAFT, mergeable.
- Automatyczne workflow uruchomione przez GitHub po aktualizacji plików: AiFORTeca Unit Tests run #872 oraz Gradle Wrapper Validation run #1620 — w chwili checkpointu IN_PROGRESS. Nie deklarować PASS przed zakończeniem i sprawdzeniem raportów.
- Android Build/APK i test urządzenia: NOT RUN / NOT VERIFIED.
- Etap 34 zamknięty w zakresie polityki i naprawy brakującego `AGENT_RESULT`: repair jest ograniczony do istniejącego raportu + terminalnego checkpointu + dokładnego `runId`; nie odtwarza wywołań providera.
- CI na końcowym HEAD `0d427573022dcdb3c106c5010a8d0c830c7227f2`: AiFORTeca Unit Tests #898 — SUCCESS; Gradle Wrapper Validation #1646 — SUCCESS.
- Pełny Android Build/APK: NOT RUN / NOT VERIFIED. PR #3 pozostaje OPEN/DRAFT.


## Uzupełnienie etapu 34 — zabezpieczenia uzgadniania (2026-11-26, CI PASS)

Baza kodowa: `61ec41c6a7bcbf4a70bc4e6cd61e5aefd5b1b2e2`.

Cztery poprawki funkcjonalne:
1. Wybór checkpointu najpierw ogranicza dane do dokładnego `runId`, a dopiero potem wybiera najnowszy wpis dla execution/task. Nowszy checkpoint z kolejnego retry nie ukryje już właściwego checkpointu starszego, nadal uzgadnianego uruchomienia.
2. Polityka uzgadniania odrzuca dowody przypisane do agenta spoza listy przydzielonej do zadania; nie pomija już obcego źródła jako nieistotnego.
3. Polityka fail-closed odrzuca wielokrotne raporty archiwalne dla tego samego agenta w jednym uzgadnianym wykonaniu, zamiast arbitralnie polegać na kolejności listy.
4. Polityka fail-closed odrzuca wielokrotne zdarzenia `AGENT_RESULT` dla tego samego agenta; nie wybiera arbitralnie jednego z nich.

Dodano cztery testy regresyjne dla powyższych przypadków. **Testów nie uruchamiano w ramach tej pracy**; zostaną objęte wspólnym CI po domknięciu pakietu. Nie zmieniono routingu providerów, budżetu tokenów, checkpointów źródłowych ani mechanizmu ponawiania wywołań API.


## Pakiet 35 — wiarygodność pochodzenia raportów i checkpointów (2026-11-26, w toku)

- Baza: `0d427573022dcdb3c106c5010a8d0c830c7227f2`.
- Zakres czterech poprawek:
  1. Odczyt raportu wykonania wymaga zgodności `agentId` wpisu archiwum z agentem przypisanym do zadania.
  2. Wyłączono fallback do starego klucza archiwum, gdy skracanie identyfikatorów rodzica/agenta mogłoby powodować kolizję; zachowano odczyt jednoznacznych krótkich kluczy legacy.
  3. Wybór checkpointów odrzuca wykonanie, dla którego wpisy tego samego `taskId` i `runId` wskazują różne `agentId`; kolejność dziennika nie rozstrzyga konfliktu tożsamości.
  4. Ocena uzgadniania wiąże checkpoint z dokładnym `taskId`, `agentId` i `runId` raportu; obcy checkpoint nie może autoryzować naprawy ani wyznaczać statusu końcowego.
- Dodano testy regresyjne archiwum i czytnika dla błędnej tożsamości agenta, kolizji skróconego klucza legacy i obcego checkpointu.
- Testów nie uruchamiano w trakcie pojedynczych zmian. Pełne CI po domknięciu pakietu; APK/build Android pozostają osobnym statusem.
