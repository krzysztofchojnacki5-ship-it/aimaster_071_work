# AI Master Pro Android — Fuzja OpenCode / Multi-Agent (0.2.0)

## Co zostało wdrożone w kodzie
- Dodano katalog 67 profili specjalistycznych (`agents/AgentCatalog.kt`).
- Dodano dobór 1–4 specjalistów na podstawie słów kluczowych (`AgentCatalog.selectForPrompt`).
- Dodano silnik równoległych zadań (`agents/TeamRunEngine.kt`) z limitem 4 równoczesnych wywołań.
- MASTER uruchamia wybrane role równolegle, zbiera raporty i wykonuje syntezę. Równoległość skraca czas oczekiwania przy niezależnych zadaniach, ale nie gwarantuje mniejszego zużycia tokenów; może zwiększyć liczbę wywołań.
- Domyślnie wszystkie role w danym batchu korzystają z jednego dostawcy/modelu MASTER-a; nie wymagają osobnych kluczy API.
- Dodano trzy tryby: Planowanie, Diagnostyka i Automat; wybór jest zapisywany per rozmowa.
- Dodano kontrolę unikalności zadań, deklarowanych zakresów i nakładających się ścieżek.
- Dodano obsługę bezkluczowego profilu Ollama dla lokalnego endpointu.
- W transporcie HTTP dopuszczono wyłącznie prywatne adresy/lokalne hosty bez klucza API; zewnętrzne usługi powinny używać HTTPS.
- Dostosowano wrapper Gradle do 8.13, zgodnego z AGP 8.13.2.

## Istotne różnice wobec oryginalnego forka
- To natywna implementacja Android/Kotlin inspirowana publiczną architekturą autora, a nie build OpenCode.
- Katalog 67 nazw i odpowiedzialności pochodzi z publicznego przeglądu autora. Pełne, oryginalne pliki promptów agentów nie zostały skopiowane do tej paczki, ponieważ nie udało się pobrać kompletnego katalogu źródłowego z GitHub w tym środowisku. Nie przedstawiamy wygenerowanych promptów jako oryginalnych.
- Router specjalistów jest deterministyczny i oparty o słowa kluczowe. Nie jest jeszcze semantycznym routerem LLM.
- TeamRunEngine wykonuje równoległe wywołania modeli i zbiera raporty. Nie uruchamia narzędzi systemowych, nie czyta repozytorium użytkownika i nie edytuje plików.
- Tryb Automat oznacza w tej wersji analizę i przygotowanie odpowiedzi; aplikacja nie ma jeszcze bezpiecznego runtime do wykonywania zmian w zewnętrznym projekcie.
- Zakresy zapisu są walidowane jako kontrakt koordynacyjny, ale nie stanowią sandboxa. Ponieważ aktualna aplikacja nie wykonuje zapisu do repozytorium, zadania MASTER są uruchamiane z pustym `writeScope`.
- Brak automatycznego przełączania na płatnego dostawcę. Nie dodano płatnych usług ani zależności. Nie dodano jeszcze licznika tokenów ani twardego limitu budżetu na rozmowę.

## Koszty
- Definicje agentów są bezpłatnymi plikami/klasami.
- Wspólny model oznacza, że nie potrzeba 67 osobnych API.
- Wywołania modelu nadal mogą zużywać płatne tokeny, zależnie od wybranego dostawcy i modelu.
- Ollama nie pobiera opłat za API, ale wymaga działającego serwera/modelu na komputerze lub innym urządzeniu w sieci.

## Testy i ograniczenia środowiska
- Sprawdzono zawartość i spójność ZIP-a wejściowego, liczbę profili (67), integrację źródeł i aktualizację konfiguracji Gradle.
- Nie wykonano kompilacji Androida: w środowisku nie ma zainstalowanego Gradle, Android SDK ani kotlinc, a dostęp sieciowy do pobrania dystrybucji Gradle jest zablokowany.
- Przed użyciem należy otworzyć projekt w Android Studio, zsynchronizować Gradle i wykonać Build > Make Project.
- Jeżeli kompilacja zgłosi błędy, należy poprawić je przed instalacją APK.

## Źródło i licencja
- Źródło: https://github.com/danbak0757/opencode
- Publiczny opis: `CUSTOM-VERSION.md`
- Autor deklaruje fork OpenCode z trzema trybami, 67 specjalistami i `team_run`.
- Licencja repozytorium: MIT. Zachowaj oryginalne informacje licencyjne przy pobieraniu/wykorzystywaniu kodu.
