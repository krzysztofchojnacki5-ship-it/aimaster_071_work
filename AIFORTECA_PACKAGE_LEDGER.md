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
