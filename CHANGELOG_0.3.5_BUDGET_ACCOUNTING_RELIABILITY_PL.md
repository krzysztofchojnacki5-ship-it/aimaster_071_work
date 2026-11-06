# AL MASTER PRO 0.3.5 — Dokładność tokenów i niezawodność zadań

## Poprawki wykryte podczas przeglądu 0.3.4

### 1. Gemini przez endpoint OpenAI-compatible — poprawne szacowanie tokenów
- Gdy odpowiedź nie zawiera `usage`, poprzedni kod mógł zapisać `-2` jako `totalTokens`.
- Ujemna wartość mogła zostać pominięta w zliczaniu budżetu dziennego.
- Teraz brakujące input/output są szacowane na podstawie długości promptu i odpowiedzi, a suma jest nieujemna.
- Dodano test regresyjny dla odpowiedzi Gemini bez pola `usage`.

### 2. Niestandardowe profile Ollama w sieci LAN
- Profile wskazujące na prywatny adres LAN i port `11434` są rozpoznawane jako lokalne/darmowe także wtedy, gdy profil ma własny identyfikator, np. `custom`.
- Publiczny host na porcie `11434` ani prywatny host na innym porcie nie są automatycznie uznawane za darmowe.
- Dodano testy klasyfikacji prywatnych i publicznych endpointów.

### 3. TeamRunEngine — rozliczanie i przerwania
- Zużycie tokenów jest zapisywane po każdej poprawnej odpowiedzi HTTP, również gdy treść raportu jest pusta.
- Po przekroczeniu 90 sekund przyszłe zadanie jest anulowane przez `Future.cancel(true)`.
- Przerwane zadanie nie powinno uruchamiać kolejnego fallbacku, gdy wątek zgłasza przerwanie; ogranicza to ryzyko dodatkowego żądania po anulowaniu.

## Zachowane zasady
- Nie zmieniono kluczy API, endpointów, przypisań providerów ani modeli.
- Nie dodano żadnego zewnętrznego serwera ani płatnej usługi.
- Historia i metryki nadal pozostają lokalne.

## Weryfikacja
- Kontrola Kotlin CLI z tymczasowymi stubami: `TeamRunEngine` + `ProviderQuotaScheduler` — PASS.
- Kontrola Kotlin CLI z tymczasowymi stubami: `ProviderClient` — PASS.
- Kontrola Kotlin CLI z tymczasowymi stubami: `FreeModelsCatalog` — PASS.
- Ręczny smoke test klasyfikacji prywatnych/publicznych endpointów Ollama — PASS.
- Testy JUnit/Robolectric nie zostały uruchomione.
- Pełny build Android nie został wykonany. Próba Gradle zatrzymała się przed startem Gradle z powodu `UnknownHostException: services.gradle.org`.
- APK nie został zbudowany.
