# AI MASTER PRO 0.2.2 — inteligentny routing agentów

## Najważniejsza zmiana: MASTER nie uruchamia zespołu bez potrzeby

- Dodano lokalny, deterministyczny `AgentRoutingPolicy`. Klasyfikacja nie wykonuje dodatkowego zapytania API i nie zużywa tokenów.
- Zwykłe rozmowy, powitania i ogólne pytania są obsługiwane bezpośrednio przez MASTER-a: 0 wywołań specjalistów.
- Załącznik sam w sobie nie uruchamia agentów. MASTER może przeanalizować go samodzielnie.
- Zadanie specjalistyczne domyślnie uruchamia dokładnie jednego najlepiej dopasowanego agenta.
- Mały zespół (maks. 3) jest uruchamiany automatycznie tylko dla jawnie rozpoznanego zadania wieloetapowego i wielodziedzinowego.
- Zespół (maks. 4) może zostać uruchomiony na wyraźne żądanie użytkownika.
- Usunięto niebezpieczny fallback `AgentCatalog.selectForPrompt`: brak dopasowania oznacza pustą listę agentów, a nie domyślne uruchomienie trzech specjalistów.
- Dodano testy routingu dla powitania, zwykłego pytania, zadania technicznego, zlecenia całemu zespołowi i złożonego zadania wielodziedzinowego.
- W logach widoczny jest tryb routingu, liczba wywołań specjalistów i powód decyzji.

## Architektura

Pozostawiono natywny silnik Kotlin/Android `TeamRunEngine` jako warstwę wykonawczą. Dla tej aplikacji jest to celowy wybór: nie wymaga osobnego serwera Python, Dockera ani utrzymywania dodatkowego backendu LangGraph. Nowy router działa przed silnikiem i decyduje, czy silnik w ogóle ma zostać uruchomiony.

## Zachowane zabezpieczenia z 0.2.1.1

- Wspólny, jawnie wybrany model dla batcha.
- Brak przypadkowego przełączania na płatne profile.
- Brak cichego maskowania błędów 400/401/403/404.
- Failover limitu wyłącznie dla 429 / RATE_LIMIT_QUOTA.

## Weryfikacja

- Dodano testy jednostkowe polityki routingu.
- Test logiki routingu skompilowano niezależnie kompilatorem Kotlin; 5 scenariuszy smoke test zakończyło się PASS.
- Pełny test Gradle/Android nie został wykonany: wrapper próbował pobrać Gradle 8.13, ale środowisko nie miało połączenia DNS z `services.gradle.org`; Android SDK również nie jest skonfigurowany. Nie deklarujemy zatem potwierdzonego builda APK.
