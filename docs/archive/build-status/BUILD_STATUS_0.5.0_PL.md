# Status AL MASTER PRO 0.5.0

- Baza źródłowa: 0.3.9
- Wersja: 0.5.0
- versionCode: 22
- Pełny projekt Android Studio; APK nie jest dołączony.

## Najważniejsze zmiany
- Lokalny ranking kompetencji agentów oparty o feedback, próg 5 niezależnych zadań, wygładzanie wyniku i wyłącznie tie-breaker dopasowania słów kluczowych.
- Oceny i metadane pod każdą odpowiedzią czatu, z trwałym powiązaniem przez requestTaskId.
- Naprawa nawigacji i zachowania dedykowanego czatu Asystenta.
- Realne anulowanie aktywnych żądań HTTP przez niezmienny RequestScope; blokada spóźnionych wywołań batcha również po rozpoczęciu nowego zadania.
- Synchronizacja dziennika komunikacji agentów i renderowanie migawkowe przy równoległych callbackach.
- Korelacja tokenów i faktycznego providera/modelu z pojedynczym żądaniem.
- Logi agentów izolowane do rozmowy, która uruchomiła zadanie.
- Migracja starszych rozmów przez puste znaczniki identyfikatorów odpowiedzi; brak zmian w starych treściach wiadomości.

## Weryfikacja
- Izolowana kompilacja ProviderClient ze stubami JSON: PASS.
- Lokalny test HTTP anulowania POST i brak retry: PASS.
- Izolowana kompilacja/test rankingu agentów: PASS.
- Izolowana kompilacja/test rankera jakości: PASS.
- Parser Kotlin MainActivity: brak błędów składniowych wykrytych przez kontrolę kompilatora; pełna analiza typów wymaga Android SDK i zależności.
- Pełny Gradle build / Android unit tests: NIEURUCHOMIONE — brak dostępu DNS do `services.gradle.org` (`UnknownHostException`).
- Android SDK: niedostępny w środowisku.
- APK: nie wygenerowano.

## Kompatybilność danych
- Zachowano istniejące SharedPreferences, konfiguracje providerów, klucze API i historię rozmów.
- Nowe pole `assistantTaskIds` jest opcjonalne; starsze rozmowy są odczytywane bez migracji destrukcyjnej.
- Nie zmieniono schematu kopii zapasowych ani szyfrowania.
