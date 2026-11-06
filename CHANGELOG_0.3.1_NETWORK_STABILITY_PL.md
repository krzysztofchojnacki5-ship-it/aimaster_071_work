# AL MASTER PRO 0.3.1 – stabilność komunikacji sieciowej

## Zmiany
- ProviderClient rozróżnia DNS_ERROR, CONNECT_ERROR, CONNECT_TIMEOUT, RESPONSE_TIMEOUT, READ_TIMEOUT, NETWORK_IO, błędy HTTP i nieprawidłowe odpowiedzi.
- Zachowywany jest oryginalny komunikat wyjątku oraz host i faza żądania.
- Retry do 3 prób z exponential backoff i jitterem dla błędów DNS/połączenia; GET może dodatkowo ponowić timeout odczytu i HTTP 5xx.
- POST jest ponawiany wyłącznie po błędzie DNS/połączenia, gdy nie uzyskano odpowiedzi HTTP. Timeout odczytu POST nie jest powtarzany, aby nie ryzykować podwójnego rozliczenia.
- Strumienie żądań i odpowiedzi są zamykane przez `use`; połączenie jest zawsze rozłączane.
- TeamRunEngine izoluje błąd DNS/połączenia do konkretnego agenta i pomija kolejne profile wskazujące na ten sam host w ramach tego zadania. Nie ma globalnego wyłączania dostawcy.
- Domyślna równoległość pozostaje ograniczona do 4 zadań; maksymalna wartość konfiguracyjna jest ograniczona do 8.
- Nie zmieniono kluczy, nazw modeli, identyfikatorów modeli ani endpointów providerów.

## Dodane testy
- Lokalny HTTP: pojedyncze żądanie OpenAI-compatible.
- Lokalny HTTP: GET po HTTP 503, następnie sukces (retry).
- Lokalny HTTP: timeout odczytu POST i potwierdzenie braku ponowienia.
- DNS: host `.invalid` i oczekiwana kategoria DNS_ERROR.
- TeamRunEngine: 7 agentów, równoległość maks. 4, jeden celowo zwraca HTTP 500, pozostali kończą zadania.

## Ograniczenia weryfikacji
- Pełne testy Gradle nie mogły zostać uruchomione w środowisku przygotowawczym: Gradle Wrapper nie pobrał Gradle 8.13, ponieważ `services.gradle.org` nie rozwiązał się przez DNS (`UnknownHostException`).
- Nie wykonano rzeczywistego wywołania OpenRouter z kluczem użytkownika. Klucze API są przechowywane w konfiguracji aplikacji/urządzenia, nie w projekcie ZIP; testy sieciowe dodane w projekcie używają lokalnego serwera testowego.
- Testy DNS, timeoutu i odzyskiwania połączenia w tej paczce są testami automatycznymi/symulacyjnymi, nie potwierdzają stanu sieci operatora ani usługi OpenRouter w chwili uruchomienia aplikacji.
