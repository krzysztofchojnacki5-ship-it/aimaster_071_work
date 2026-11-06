> Stan 2026-11-05: bieżące źródła aplikacji mają `versionName=0.6.54`, `versionCode=80`. Pakiet 0.6.55-SOURCE zawiera wyłącznie utwardzenie szkicu pkt 4 i aktualizacje dokumentacji; nie jest nowym APK. Ten dokument zawiera historyczne opisy etapów — bieżący stan i bramki należy odczytywać z `PROJECT_WORKSPACE_ROADMAP_PL.md` oraz `BUILD_STATUS_0.6.55_PL.md`.

# Architektura — AI MASTER PRO Android (dokument historyczny; aktualizacja stanu 0.6.55-SOURCE)

## 1. Warstwy Aplikacji
- **Prezentacja (UI / View Layer)**: Czat ChatGPT-style (z panelem bocznym historii, wyszukiwaniem, przypinaniem i renderowaniem Markdown), zakładka Asystent, zakładka Rozmowy (live stream komunikacji między agentami), zakładka Monitor API (Token Tracker & Koszty), zakładka Modele oraz zakładka Agenci.
- **Konfiguracja Dostawców (`ProviderConfig`)**: Profile modeli, URL, typ protokołu oraz klucze API.
- **Klient Sieciowy (`ProviderClient`)**: Synchronizowany transport HTTP oparty o `HttpURLConnection` z obsługą timeoutów (15s connect / 45s read), nagłówków `Retry-After` oraz automatycznym ponawianiem prób dla błędów przejściowych.
- **Magazyn Bezpieczny (`SecureStore`)**: Szyfrowane przechowywanie kluczy API za pomocą systemowego **Android Keystore API** (AES/GCM/NoPadding).
- **Kryptografia Backupów (`BackupCrypto`)**: Portable, zabezpieczone hasłem kopie zapasowe przy użyciu PBKDF2WithHmacSHA256 (210 tys. iteracji) + AES-GCM ze ścisłą walidacją nagłówków i wersji.
- **Trwała Pamięć Agentów (`AiMemoryStore`)**: Ciągłe zapisaną pamięć reguł i zasad uczenia dla modeli.

## 2. Multi-Agent Fusion & Orchestration
- **`AgentCatalog`**: Katalog 67 profili specjalistycznych oraz deterministyczny dobór podagentów po słowach kluczowych.
- **`WorkMode`**: Tryby pracy (Planowanie, Diagnostyka, Automat) zapisywane per rozmowa.
- **`TeamRunEngine`**: Lokalny silnik wykonawczy dla ograniczonych zadań agentów. Uruchamia wyłącznie agentów wybranych przez router, dopasowuje pulę wątków do liczby zadań, stosuje timeout 90 sekund na zadanie i przełącza się wyłącznie na jawnie przekazane profile, które przejdą kontrolę darmowego modelu. Nie ponawia w ciemno wywołań czatu po niejednoznacznym błędzie.
- **Izolacja Historii**: Historia dialogów między agentami (`agentActivity`) jest ściśle powiązana wyłącznie z bieżącym identyfikatorem rozmowy (`currentChatId`).

## 3. Security Considerations & Compliance (RODO / GDPR)
- **Zero Hardcoded Secrets**: Klucze API nigdy nie są wbudowywane w kod źródłowy ani repozytorium.
- **Hardware-backed Encryption**: Klucze API są szyfrowane i przechowywane w sprzętowo wspieranym Android Keystore.
- **Log Sanitization**: Mechanizmy logowania automatycznie maskują i usuwają potencjalne klucze API oraz tokeny (`[REDACTED]`), zapobiegając wyciekom do Logcat lub pamięci podręcznej.
- **Local-First**: Wszystkie dane czatów, statystyki tokenów (`ApiTokenTracker`) i kopie zapasowe są przechowywane lokalnie na urządzeniu użytkownika.

## 4. Ograniczenia i Ścieżka Rozwoju
- Aplikacja nie posiada autonomicznego środowiska (sandbox/terminala) do modyfikowania plików projektu na dysku.
- W przyszłych iteracjach planowane jest rozszerzenie testów integracyjnych z mock serverem oraz dalsza optymalizacja metryk zużycia zasobów.


## Demand-driven agent orchestration (0.2.2)

`AgentRoutingPolicy` is a local, deterministic pre-router. It does not call an LLM and therefore adds no classification-token cost. It returns one of three decisions:

- `MASTER_ONLY`: answer with the selected MASTER model; no specialist request is made.
- `ONE_SPECIALIST`: delegate to one best-matching specialist and let MASTER synthesize the report.
- `TEAM`: use up to three specialists for an automatically recognized cross-domain task, or up to four when the user explicitly requests a team.

An attachment alone never triggers delegation. `AgentCatalog.selectForPrompt` returns an empty list when no specialist keywords match; it no longer invents a default team. The existing Kotlin `TeamRunEngine` remains the execution layer, avoiding a mandatory Python/Docker/LangGraph server for an Android-first app.


## Orkiestracja 0.3.0 — Android-only

- **Brak backendu obowiązkowego**: routing, planowanie ograniczonego zespołu i wykonanie zadań odbywają się lokalnie w Kotlinie. Dostawcy LLM nadal wymagają połączenia internetowego; lokalny Ollama wymaga dostępnego endpointu, ale nie wymaga hostowania silnika agentów na komputerze.
- **Demand-driven routing**: `MASTER_ONLY` nie wywołuje specjalistów; `ONE_SPECIALIST` deleguje do jednego; `TEAM` jest ograniczony do trzech agentów automatycznie i czterech po jawnej prośbie użytkownika.
- **Profile per rola**: `AgentProviderRouting` mapuje specjalistów katalogu do istniejących przypisań ról czatu (DEBUGGER, RESEARCHER, DEVELOPER, REVIEWER, WRITER, ASSISTANT). Przed wykonaniem profil musi mieć model, endpoint i wymagane dane uwierzytelniające oraz przejść kontrolę darmowego statusu.
- **Ochrona kosztów**: podstawowy profil MASTER jest używany tylko wtedy, gdy jest sklasyfikowany jako FREE/FREE TIER; fallback jest ograniczony do jawnie przekazanych darmowych profili. Odkrycie ID modelu przez `/models` samo w sobie nie potwierdza darmowej ceny ani limitu konta.
- **Ochrona przed podwójnym naliczeniem**: automatyczne retry HTTP jest ograniczone do GET; żądania generowania odpowiedzi POST nie są automatycznie powtarzane po niejednoznacznym błędzie sieci.
- **Granice kontekstu**: agent otrzymuje tylko kontekst faktycznie przekazany w zadaniu i ma obowiązek zgłosić brakujące dane; nie wolno mu zakładać dostępu do całego repozytorium.

## 0.5.0 — lokalna jakość agentów i zakres anulowania

- `AgentQualityRanker` jest czystą funkcją lokalną: oceny agentów z minimum 5 unikalnych `targetId` tworzą wygładzony wynik jakości. `AgentRoutingPolicy` używa go wyłącznie jako tie-breakera dla agentów o takim samym wyniku dopasowania słów kluczowych.
- `ProviderClient.RequestScope` reprezentuje jedno zadanie nadrzędne. MASTER, fallbacki i równolegli agenci muszą współdzielić ten sam obiekt. `cancelActiveRequests()` unieważnia scope i rozłącza jego połączenia; późno uruchomiony worker ze starym scope nie może wykonać kolejnego POST-a.
- Wywołania bez `RequestScope` (np. niezależne pobranie katalogu modeli) pozostają poza zakresem anulowania czatu.
- `assistantTaskIds` jest tablicą wyrównaną do kolejności wiadomości asystenta. W starszych rozmowach brakujące identyfikatory są uzupełniane pustymi wpisami z przodu, bo nowe ID dopisywane są na końcu historii.
- Dziennik komunikacji agentów jest zapisywany pod ID rozmowy, która uruchomiła pracę; zapis i migawki listy są synchronizowane, aby callbacki równoległych agentów nie nadpisywały sobie wpisów.
