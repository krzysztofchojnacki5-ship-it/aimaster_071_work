# AI Master Pro Android 0.3.2 — Smart Token Budget & Model Scheduler

## Zmiany
- Dodano planowanie budżetu całej partii agentów przed uruchomieniem równoległych zapytań. Szacunek obejmuje wejście (heurystyka znaków/4) oraz rezerwę wyjścia do 4096 tokenów na agenta.
- Wykorzystano istniejące limity dzienne, per-agent i miesięczny limit kosztów. Jeśli budżet nie wystarcza, część zadań jest pomijana z czytelnym powodem; jeśli nie mieści się żadne, żądania nie są wysyłane.
- Dodano współdzielony scheduler profili provider/model: ogranicza równoległe żądania do tego samego darmowego modelu, rozsuwa starty i stosuje rosnący cooldown po rzeczywistym HTTP 429. Cooldown jest per host/provider/model, a nie globalny.
- Scheduler działa zarówno dla czatu bezpośredniego, jak i TeamRunEngine.
- Darmowe/lokalne modele rozpoznane przez FreeModelsCatalog otrzymują zerową stawkę w kalkulacji kosztów, zamiast domyślnej stawki modelu płatnego.
- Zabezpieczono zapisy i odczyty rejestru tokenów synchronizacją, aby równoległe odpowiedzi agentów nie nadpisywały sobie historii.
- Skrócono wspólny prompt AgentCatalog, ograniczając powtarzane instrukcje przy każdym agencie bez usuwania zasad dowodowości i formatu raportu.
- Dodano synchronizację metod recordUsage/getUsage/resetUsage w starszym TokenTracker, aby równoległe wywołania nie modyfikowały wspólnej mapy bez blokady.
- Skrócono i skorygowano ProjectCodebase: nie twierdzi już fałszywie, że pliki, kompilacja i testy zostały zweryfikowane. Wyraźnie informuje agentów, że przekazany kontekst jest tylko opisem architektury.
- Dodano testy jednostkowe schedulera (serializacja darmowego modelu i izolacja cooldownu po 429).

## Ważne ograniczenia
- Szacowanie wejścia przez długość tekstu / 4 jest przybliżeniem, a 4096 tokenów wyjścia jest rezerwą, nie rzeczywistym zużyciem.
- Dostawcy nie udostępniają jednolitego, gwarantowanego API do odczytu limitów RPM/TPM. Scheduler nie udaje, że zna dokładny limit konta; reaguje na zaobserwowane 429 i ogranicza równoległość.
- Cooldown jest przechowywany w pamięci procesu; restart aplikacji go resetuje.
- Mechanizm działa bez otwierania ekranu „Monitor API”; nie naprawia jeszcze samego widoku Monitor API, lecz korzysta bezpośrednio z zapisanych danych trackera i limitów.
- Zmiany nie dodają płatnych usług ani nie zmieniają kluczy, modeli lub endpointów.

## Weryfikacja
- Testy JUnit schedulera dodane, ale nieuruchomione w Gradle. Niezależne smoke testy Kotlin CLI wykonane: scheduler (`SCHEDULER_SMOKE_TEST_OK`) oraz AgentCatalog (`AGENT_CATALOG_SMOKE_TEST_OK`, 67 agentów, niepuste prompty i sekcja dowodów).
- Pełna kompilacja Android/Gradle i testy nie zostały wykonane; wymagają środowiska Gradle/Android SDK.
