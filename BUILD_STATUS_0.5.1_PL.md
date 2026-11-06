# Status AL MASTER PRO 0.5.1

- Baza źródłowa: 0.5.0
- Wersja: 0.5.1
- versionCode: 23
- Pełny projekt Android Studio; APK nie jest dołączony.

## Zmiany
- Naprawiono przełączanie z dedykowanego czatu Asystenta do czatu MASTER-a po ocenie odpowiedzi i usunięciu załącznika.
- Rozdzielono ranking ocen raportów agentów od ocen końcowych odpowiedzi.
- Dodano ostrożne wygładzanie ocen kompetencji w kategoriach z małą liczbą próbek.
- Dodano klikalne linki Markdown HTTP/HTTPS w odpowiedziach czatu.

## Weryfikacja
- Testy czystych klas Kotlin (`AgentQualityRanker`, `ChatScreenPolicy`): uruchomione osobno kompilatorem Kotlin.
- Testy Robolectric / pełne testy Gradle: niepotwierdzone, ponieważ środowisko nie ma Android SDK ani pobranej dystrybucji Gradle 8.13.
- APK: nie wygenerowano.
- Kontrola składni `MainActivity.kt`: wykonana oddzielnie; brak błędów parsera Kotlin.

## Dane i bezpieczeństwo
- Zachowano istniejące SharedPreferences, historię rozmów, pamięć, klucze API i konfiguracje providerów.
- Nie dodano zależności, nowych uprawnień ani ruchu sieciowego.
- Nowe rankingi i polityka nawigacji działają lokalnie i nie zużywają tokenów.
