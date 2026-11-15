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
- Ostatni commit pakietu: `f5997b0178c40e944d9de70bcf6c3b90cb7bbb30`
- Zakres czterech zmian:
  1. Wprowadzono osobne typy domenowe `ProviderProfile` (połączenie i referencja do sekretu) oraz `ModelProfile` (model i jego typ uruchomienia).
  2. Dodano nieinwazyjny konwerter starych `ProviderConfig`; zachowuje istniejące ID profili, ID przypisań agentów i referencję do klucza w SecureStore.
  3. Rozróżniono `LOCAL_OLLAMA` od `REMOTE_API` na podstawie zweryfikowanego endpointu, a nie nazwy modelu.
  4. Dodano atomową migrację katalogów bez kopiowania kluczy API oraz uruchomienie migracji przy starcie aplikacji; stare rekordy pozostają źródłem prawdy na czas przejściowy.
- Testy dodane: mapowanie profili, zachowanie ID i referencji sekretu, lokalny Ollama kontra API, osierocone profile, brak sekretów w nowym magazynie, idempotencja i pusty magazyn.
- CI dla ostatniego commitu: `AiFORTeca Unit Tests` — IN_PROGRESS (run `43709928814`); `Gradle Wrapper Validation` — SUCCESS (run `43709928850`).
- Status: oczekiwanie na wynik testów pakietu 2. Nie rozpoczynać pakietu 3 przed zakończeniem testów i analizą ewentualnych błędów.

## Zasady

- Cztery zmiany kodu na pakiet, następnie pełne testy CI.
- APK nie jest warunkiem kontynuacji prac, jeśli użytkownik wyraźnie polecił nie czekać; status buildu i APK nadal raportować osobno i wyłącznie na podstawie artefaktów.
- Nie oznaczać testów, buildu ani APK jako zaliczonych bez potwierdzenia z CI / artefaktów.

### Korekta po pierwszym przebiegu CI pakietu 2

- Commit korekty: `48536d022a6f738fabb59c72c957d48ea200c1d9`.
- Przyczyna niepowodzenia: test wykazał, że migrator błędnie oznaczał poprawny rekord dostawcy bez domyślnego modelu jako pominięty.
- Poprawka: rekord główny dostawcy bez modelu jest zachowywany jako połączenie; pusty model w profilu potomnym nadal jest raportowany jako pominięty.
- CI po poprawce: `AiFORTeca Unit Tests` uruchomione (run `43710507395` oraz `43710503509`); `Gradle Wrapper Validation` uruchomione (run `43710507403` oraz `43710503416`). Wynik końcowy jeszcze nieznany.
- Wcześniejszy przebieg na `9e0ef3c`: kompilacja Kotlin i kompilacja testów zakończone sukcesem; 1299 testów uruchomionych, 1 niepowodzenie; raport CI opublikowany. Nie oznaczać pakietu jako zaliczonego do czasu pełnego zielonego przebiegu na poprawionym commicie.
