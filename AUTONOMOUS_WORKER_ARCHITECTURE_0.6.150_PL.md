# AI MASTER PRO — architektura autonomicznego workera 24/7
Data: 2026-11-08
Typ: decyzja architektoniczna / dokumentacja projektowa
Baza kodu: `audit/0.6.149-single-chat-snapshot`
Base SHA: `e37c3eded7be0ac8f92532e35cbb1e6f0dcb7eca`
Status: DESIGN ONLY — bez zmian produkcyjnego kodu, bez builda i bez testów Android.

## 1. Decyzja

**Właściwym miejscem dla ciągłego wykonawcy jest zewnętrzny worker uruchomiony jako usługa na VPS/serwerze, a nie proces Android ani sesja agenta IDE.**

Aplikacja Android pozostaje panelem MASTER-a: planowanie, podgląd zadań, zatwierdzanie zakresów, budżet, diagnostyka i sterowanie (pause/resume/stop). GitHub jest trwałym kanałem wymiany zadań, kodu i raportów. Worker jest stale działającym wykonawcą, który pobiera autoryzowane zadania, deleguje pracę do modeli/agentów API, waliduje wyniki i przygotowuje branch/PR. Merge do gałęzi bazowej pozostaje ręczną decyzją właściciela.

Android WorkManager może później obsługiwać synchronizację i powiadomienia, ale nie jest gwarancją nieprzerwanej pracy 24/7: system Android może odraczać lub zatrzymywać pracę w tle, a urządzenie może zostać wyłączone, pozbawione sieci lub oszczędzać baterię.

## 2. Ustalenia z aktualnego kodu

Audyt statyczny gałęzi `audit/0.6.149-single-chat-snapshot`:

- `MasterWorkJournalStore` zapisuje ograniczone rekordy pracy w SharedPreferences z potwierdzanym commit. Obsługiwane statusy to `PLANNED`, `ACTIVE`, `BLOCKED`, `PAUSED`, `COMPLETED`.
- `pauseActive()` oznacza pracę pozostawioną przez poprzedni proces jako `PAUSED`; komentarz i komunikat wymagają jawnej decyzji koordynatora. To bezpieczne odzyskiwanie, ale nie autonomiczne wznowienie.
- `TaskCheckpointStore` utrwala checkpointy i odróżnia stany niejednoznaczne od potwierdzonych. W szczególności żądanie API, które mogło zostać opłacone, nie może być bezwarunkowo wysłane ponownie po restarcie.
- `TeamRunEngine.runBatch()` tworzy pulę workerów dla pojedynczego batcha i zamyka ją po zakończeniu. To orkiestracja zadania, nie trwały scheduler kolejki.
- `AndroidManifest.xml` nie deklaruje usługi workerowej ani komponentu startowanego po restarcie.
- `app/build.gradle.kts` nie zawiera zależności WorkManager. Nie ma więc podstaw, by twierdzić, że aplikacja ma już mechanizm trwałego wykonywania pracy w tle.

Wniosek: obecne komponenty są wartościowym fundamentem orkiestracji, checkpointów i budżetu, ale nie zapewniają samodzielnej pracy po zamknięciu aplikacji.

## 3. Docelowy podział odpowiedzialności

### Android MASTER
- tworzy/porządkuje roadmapę i zadania;
- pokazuje kolejkę, stan workera, logi, koszt, tokeny i ostatni heartbeat;
- ustawia limity budżetu i dozwolone zakresy;
- zatwierdza zadania wymagające uprawnień lub merge;
- może wysłać PAUSE/STOP/RESUME.

### GitHub Work Bridge
- autoryzowane zadania ze stabilnym ID i statusem `READY`;
- dokładna allowlista plików, baza SHA i kryteria akceptacji;
- oddzielny branch na każde zadanie;
- raporty i artefakty testów;
- PR jako granica przeglądu, bez automatycznego merge.

### VPS Worker
- proces usługi zarządzany przez systemd lub kontener z polityką restartu;
- polling INBOX/źródła zadań z backoffem i jitterem;
- trwała lokalna kolejka SQLite z lease, heartbeat, attempt count i idempotency key;
- pobranie dokładnego base SHA, utworzenie izolowanego worktree/branch;
- wywołanie modelu/agentów API z limitami kosztu, tokenów i czasu;
- walidacja diffu względem allowlisty, testy i build tylko w sandboxie;
- publikacja branch + raport + PR; brak automatycznego merge;
- restart po awarii z odzyskaniem lease i stanów bezpiecznych.

## 4. Cykl zadania

1. Poll: odczytaj aktualny INBOX z zaufanej gałęzi bazowej.
2. Authorize: zaakceptuj tylko dokładnie jedno zadanie o statusie `READY`; sprawdź ID, base SHA, branch i allowlistę.
3. Claim: utwórz lokalny lease w SQLite; jeden worker = jeden aktywny claim. Nie zmieniaj samowolnie statusu INBOX.
4. Prepare: utwórz izolowany worktree od wskazanego SHA; sprawdź czysty stan i dokładną listę ścieżek.
5. Execute: agent przygotowuje propozycję patcha; worker nie zapisuje bezpośrednio treści odpowiedzi do plików.
6. Validate: sprawdź ścieżki, rozmiary, sekrety, diff, testy i build. Brak uruchomienia oznacza `NOT_RUN`, nie PASS.
7. Publish: wypchnij wyłącznie branch zadaniowy i raport; utwórz PR tylko wtedy, gdy autoryzacja zadania na to pozwala.
8. Finish: zapisz wynik, SHA, logi, koszty i heartbeat; zwolnij lease.
9. Continue: pobierz następne zadanie READY. Brak zadań oznacza idle/poll, nie zakończenie usługi.

## 5. Maszyna stanów i odzyskiwanie

Stan lokalnego wykonawcy:
`DISCOVERED → CLAIMED → PREPARING → RUNNING → VALIDATING → PUBLISHING → WAITING_REVIEW → COMPLETED`

Stany alternatywne:
`RETRY_WAIT`, `BLOCKED`, `FAILED`, `CANCEL_REQUESTED`, `INTERRUPTED_REVIEW_REQUIRED`.

Zasady:
- Lease ma TTL i heartbeat; po wygaśnięciu można przejąć zadanie po sprawdzeniu ostatniego checkpointu.
- Zadanie jest idempotentne na poziomie artefaktów: stabilny Task ID, branch, patch ID i query ID.
- Nie ponawiaj automatycznie żądania modelu, jeżeli proces zginął po wysłaniu POST, ale przed trwałym zapisaniem odpowiedzi/usage. Oznacz `INTERRUPTED_REVIEW_REQUIRED`; najpierw uzgodnij usage i wynik z dostawcą lub operatorem.
- Retry jest dozwolony tylko dla faz, których brak skutków ubocznych jest udowodniony, albo dla wywołań z obsługiwaną idempotency key.
- STOP blokuje nowe zadania i anuluje aktywne żądania w sposób kontrolowany; nie usuwa checkpointów ani logów.

## 6. Bezpieczeństwo

- GitHub App/token z minimalnymi uprawnieniami; preferowany krótkotrwały token instalacyjny. Żadnych kluczy API w repozytorium, INBOX, raportach ani logach.
- Sekrety dostawców przechowywane w secret managerze/VPS environment, dostępne wyłącznie workerowi.
- Sandbox rootless/container per task: limity CPU/RAM/dysk/czas; domyślnie brak sieci dla testowanego kodu. Dostęp sieciowy tylko do jawnej listy endpointów i tylko dla procesu pobierającego zależności.
- Nie montuj sekretów ani katalogu hosta w kontenerze wykonującym kod projektu.
- Wykonuj wyłącznie dokładną allowlistę; odrzucaj symlinki/path traversal, rename/delete i pliki spoza zakresu.
- PR-only, brak auto-merge, brak bezpośredniego push do `main` i branchy audytowych.
- Osobny kill switch, dzienny/miesięczny limit kosztu, limit równoległości i limit prób.
- Companion pozostaje nieaktywny i poza zakresem.

## 7. Etapy wdrożenia

### AW-01 — kontrakt i audyt integracyjny
Zamrozić schemat zadania/raportu, lease, heartbeat, statusy, zasady retry i model kosztów. Zmapować istniejące `MasterWorkJournalStore`, `TaskCheckpointStore`, `ApiTokenTracker`, `TeamRunEngine` do kontraktu bez duplikowania źródeł prawdy.

### AW-02 — read-only poller
Uruchamialny worker pobiera INBOX, waliduje manifest READY i pokazuje kolejkę/heartbeat. Nie uruchamia modeli, nie zapisuje do repo i nie wykonuje kodu. Testy parsera, nieaktualnej bazy, duplikatów ID i statusów nie-READY.

### AW-03 — trwała kolejka i lease
SQLite, atomowe claim/renew/release, TTL, heartbeat, restart recovery i testy dwóch konkurujących workerów. Bez wykonywania kodu projektu.

### AW-04 — agent API i budżet
Integracja z dostawcami modeli, routing, limity i księgowanie. Rozstrzygnięcie niejednoznacznych żądań po awarii bez podwójnego naliczania.

### AW-05 — sandbox i patch pipeline
Izolowane worktree/container, generowanie patcha, allowlist, skan sekretów, testy, artefakty i raport.

### AW-06 — publikacja i ciągły cykl
Branch/PR, kontrola CI, pętla pobierania kolejnych zadań, monitoring i procedury STOP/PAUSE. Włączenie pracy 24/7 dopiero po testach awarii i kosztów.

## 8. Kryteria uznania „24/7”

Nie wystarczy działający timer ani otwarta sesja Android Studio. Kryteria:
- worker działa jako usługa po zamknięciu ChatGPT i Android Studio;
- automatycznie pobiera kolejne READY bez ręcznego START;
- po restarcie serwera odzyskuje lease/checkpointy bez duplikowania płatnych żądań;
- każda zmiana jest izolowana, testowana i raportowana;
- ma heartbeat, monitoring, kill switch oraz limity kosztów;
- nie scala zmian bez człowieka;
- udowodnione testami: crash/restart, duplikat poll, równoległy claim, timeout, limit kosztu, niejednoznaczny wynik API, zły diff i awaria CI.

## 9. Ograniczenia obecnego etapu

To dokument projektowy oparty na odczycie źródeł GitHub. Nie utworzono VPS, nie uruchomiono procesu 24/7, nie skonfigurowano sekretów, nie wykonano testów ani builda Android. WB-005 (smoke test CI Work Bridge) pozostaje niezależnym testem i nie jest dowodem działania workera 24/7.

**Następny bezpieczny krok:** AW-01 — zatwierdzić i przetestować kontrakt zadania/lease oraz mapowanie do istniejących magazynów; następnie AW-02 read-only poller. Nie zaczynać od uruchamiania obcego kodu ani automatycznego retry płatnych żądań.
