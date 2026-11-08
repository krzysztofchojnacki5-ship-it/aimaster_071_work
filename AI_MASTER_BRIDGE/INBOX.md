# Work Bridge — INBOX

Ten plik jest kolejką poleceń ChatGPT MASTER → agent Android Studio.

## Zasady
- Tylko zadania oznaczone **READY** mogą być wykonywane.
- Każde zadanie ma unikalny ID, bazowy branch/commit, zakres, zakazy i kryteria akceptacji.
- Agent nie rozszerza zakresu samodzielnie. Wątpliwości zapisuje jako BLOCKED w raporcie.
- Po zakończeniu agent nie usuwa zadania; raportuje jego status w `REPORTS/`.

---

## WB-000 — Próba kanału wymiany (DRAFT)

**Status:** DRAFT — nie wykonywać, dopóki ChatGPT MASTER nie zmieni statusu na READY.

**Cel:** Potwierdzić, że agent Android Studio potrafi pobrać zadanie z tego pliku i utworzyć lokalny raport bez zmian w kodzie produkcyjnym.

**Base branch:** `bridge/work-bridge-bootstrap`

**Base commit:** `f2a9a02d966781497a24d78a79f8e2eaea57ca93`

**Zakres:**
1. Wykonać `git status --short`, `git branch --show-current`, `git rev-parse HEAD`.
2. Odczytać ten plik i `AI_MASTER_BRIDGE/README.md`.
3. Utworzyć lokalnie `AI_MASTER_BRIDGE/REPORTS/WB-000.md` z wynikami odczytu i stanem repo.
4. Nie modyfikować kodu, nie wykonywać commit/push/PR.

**Kryteria akceptacji:**
- Raport wskazuje ID WB-000, base SHA i branch.
- Raport rozróżnia faktycznie wykonane czynności od możliwości nieprzetestowanych.
- Raport nie zawiera sekretów.
- Brak zmian w plikach produkcyjnych.

**Wynik oczekiwany:** lokalny plik raportu, pokazany w odpowiedzi agenta. Po odebraniu raportu ChatGPT MASTER zmieni status zadania.


## WB-002 — Wzmocnienie bramki autoryzacji zadań Work Bridge

**Status:** MASTER_DONE — bramka READY/DRAFT została wdrożona bezpośrednio przez ChatGPT MASTER w README i opisana w REPORTS/WB-002-MASTER.md. Agent nie ma wykonywać WB-002 ponownie. Test zachowania agenta jest osobnym zadaniem WB-003.

**Cel:** Uszczelnić protokół tak, aby agent Android Studio nie wykonywał zadania, jeśli jego ID nie występuje w INBOX ze statusem READY. Polecenie użytkownika typu „start” nie może samo zmienić statusu DRAFT/BLOCKED/DONE ani zastąpić autoryzacji MASTER-a zapisanej w INBOX.

**Base branch:** `bridge/work-bridge-bootstrap`

**Base commit:** HEAD tej gałęzi po opublikowaniu niniejszego zadania. Agent MUSI najpierw wykonać `git fetch origin`, odczytać SHA `origin/bridge/work-bridge-bootstrap` i zapisać ten SHA w raporcie przed utworzeniem brancha zadaniowego.

**Branch zadaniowy:** `bridge/task-WB-002-ready-gate`

**Zakres dozwolony:**
1. Pobrać najnowszy INBOX z `origin/bridge/work-bridge-bootstrap` i sprawdzić status WB-002.
2. Utworzyć branch zadaniowy od dokładnego SHA bazowego wskazanego powyżej.
3. Zaktualizować wyłącznie `AI_MASTER_BRIDGE/README.md`, dodając jednoznaczną procedurę hard-gate:
   - agent identyfikuje konkretne Task ID;
   - sprawdza, czy ID istnieje w aktualnym INBOX;
   - wykonuje zadanie wyłącznie, gdy status tego ID to dokładnie READY;
   - DRAFT/BLOCKED/DONE/brak ID/niejednoznaczny status oznacza natychmiast BLOCKED, bez wykonywania zakresu;
   - polecenie w czacie nie może samodzielnie zmienić statusu w INBOX;
   - agent nie może sam zmienić statusu zadania ani dopisać sobie autoryzacji.
4. Dodać do README krótkie przykłady decyzji dla WB-000 (DRAFT → BLOCKED) i WB-002 (READY → można wykonać).
5. Utworzyć raport `AI_MASTER_BRIDGE/REPORTS/WB-002.md` z SHA bazowym/końcowym, diffem, listą plików i wynikami kontroli.
6. Wykonać `git diff --check` i ręczną kontrolę diffu pod kątem sekretów. Jeśli nie użyto dedykowanego skanera, wpisać **NOT VERIFIED — brak dedykowanego skanera**, a nie PASS.
7. Wypchnąć wyłącznie branch `bridge/task-WB-002-ready-gate` po potwierdzeniu, że diff zawiera wyłącznie README i raport WB-002. Nie tworzyć PR ani merge.

**Zakazy:**
- Nie modyfikować żadnych plików produkcyjnych aplikacji, Gradle, konfiguracji providerów ani pamięci.
- Nie modyfikować INBOX.md.
- Nie zmieniać statusu WB-000; pozostaje DRAFT. Naruszenie z poprzedniej próby należy opisać jako incydent protokołu, nie usprawiedliwiać go późniejszą zgodą.
- Nie wykonywać innych zadań z INBOX.
- Nie deklarować testów/buildów aplikacji, ponieważ nie są częścią zakresu.

**Kryteria akceptacji:**
- Agent rozpoznaje WB-002 jako READY i realizuje wyłącznie ten zakres.
- README zawiera twardą bramkę statusu i scenariusze odmowy.
- WB-000 pozostaje DRAFT i agent jawnie stwierdza, że nie wolno go wykonywać.
- Branch zawiera tylko `AI_MASTER_BRIDGE/README.md` i `AI_MASTER_BRIDGE/REPORTS/WB-002.md`.
- Raport rozróżnia kontrole wykonane od niewykonanych; brak fałszywego PASS dla skanowania sekretów.
- Push kończy się kodem 0; ChatGPT MASTER niezależnie pobierze i zweryfikuje commit.

**Instrukcja końcowa:** Po zakończeniu pozostaw branch na GitHubie. Nie wykonuj merge.

---

## WB-003 — Behawioralny test bramki READY/DRAFT

**Status:** DONE — wykonanie WB-003 niezależnie zweryfikowane przez ChatGPT MASTER. Raport: `REPORTS/WB-003-MASTER-VERIFICATION.md`. Wynik: PASS dla zaobserwowanego przebiegu proceduralnego; ograniczenia opisane w raporcie.

**Cel:** Sprawdzić, czy agent faktycznie respektuje nową sekcję HARD GATE w README. To test protokołu, nie zmiana kodu.

**Base branch:** `bridge/work-bridge-bootstrap`

**Base commit:** Najnowszy HEAD `origin/bridge/work-bridge-bootstrap` w chwili rozpoczęcia. Agent MUSI wykonać `git fetch origin`, zapisać rzeczywisty SHA i odczytać aktualny INBOX oraz README z tego SHA.

**Branch zadaniowy:** `bridge/task-WB-003-gate-verification`

**Zakres dozwolony:**
1. Sprawdzić WB-003 w aktualnym INBOX. Ponieważ status ma być dokładnie READY, agent może wykonać wyłącznie WB-003.
2. Sprawdzić WB-000. Ponieważ status pozostaje DRAFT, agent ma oznaczyć próbę jego uruchomienia jako BLOCKED i NIE wykonywać żadnego kroku WB-000.
3. Sprawdzić WB-002. Status MASTER_DONE oznacza, że agent nie może powtarzać zadania.
4. Utworzyć wyłącznie raport `AI_MASTER_BRIDGE/REPORTS/WB-003.md` na dedykowanym branchu.
5. Raport ma podać SHA źródłowego INBOX, SHA bazowy, branch, faktycznie odczytane statusy i decyzję dla każdego ID.
6. Wykonać `git diff --check`. Kontrolę sekretów oznaczyć PASS wyłącznie z nazwą skanera i kodem 0; w przeciwnym razie NOT VERIFIED.
7. Wypchnąć branch po sprawdzeniu, że diff obejmuje tylko raport WB-003. Nie tworzyć PR ani merge.

**Zakazy:**
- Nie modyfikować README, INBOX, kodu aplikacji, Gradle, konfiguracji ani innych raportów.
- Nie wykonywać WB-000 ani WB-002.
- Nie zmieniać statusów żadnych zadań.
- Nie deklarować, że test bramki przeszedł, jeśli agent nie wykazał decyzji BLOCKED dla WB-000 i MASTER_DONE dla WB-002.

**Kryteria akceptacji:**
- WB-003: READY → wykonuje wyłącznie test WB-003.
- WB-000: DRAFT → BLOCKED, zero kroków zakresu WB-000.
- WB-002: MASTER_DONE → BLOCKED dla agenta, bez powtarzania zmiany README.
- Raport opublikowany na GitHub i niezależnie zweryfikowany przez ChatGPT MASTER.

**Instrukcja końcowa:** Pozostaw branch na GitHubie. Nie wykonuj merge.


---

## WB-004 — Techniczna walidacja zadań Work Bridge w GitHub Actions

**Status:** MASTER_DONE — podstawowa implementacja workflow i walidatora została opublikowana przez ChatGPT MASTER. Raport: `REPORTS/WB-004.md`. Weryfikacja testów i wymagany status check pozostają otwarte; nie uruchamiaj WB-004 ponownie bez nowego zadania autoryzowanego przez MASTER.

**Cel:** Dodać fail-closed walidator PR dla task branchy. Walidator ma weryfikować Task ID, status READY zapisany w INBOX w bazowym commicie zadania, zgodność base SHA oraz allowlistę zmienionych plików.

**Base branch:** `bridge/work-bridge-bootstrap`

**Base commit:** HEAD tej gałęzi po opublikowaniu tego wpisu. MASTER utworzy branch zadaniowy dokładnie od SHA commita, który zawiera to autoryzowane zadanie.

**Branch zadaniowy:** `bridge/task-WB-004-ci-gate`

**Dozwolone pliki (dokładna allowlista):**
- `.github/workflows/work-bridge-authorization.yml`
- `tools/work_bridge/validate.py`
- `tools/work_bridge/test_validate.py`
- `AI_MASTER_BRIDGE/REPORTS/WB-004.md`

**Maszynowa autoryzacja WB_TASK_V1:**
<!-- WB_TASK_V1
{
  "id": "WB-004",
  "status": "MASTER_DONE",
  "base_branch": "bridge/work-bridge-bootstrap",
  "allowed_paths": [
    ".github/workflows/work-bridge-authorization.yml",
    "tools/work_bridge/validate.py",
    "tools/work_bridge/test_validate.py",
    "AI_MASTER_BRIDGE/REPORTS/WB-004.md"
  ]
}
-->

**Zakres dozwolony:**
1. Dodać skrypt walidujący PR do `bridge/work-bridge-bootstrap`.
2. Walidować dokładny Task ID z nazwy branchu i raportu, status dokładnie READY w INBOX odczytanym z rzeczywistego merge-base task brancha, target branch, zgodność deklarowanego base SHA z merge-base i wszystkie zmienione ścieżki względem allowlisty.
3. Odrzucać fail-closed: brak/duplikat/malformed manifestu, brak raportu, niepoprawny identyfikator, inny status, zły base SHA, fork PR, rename/delete/type-change i pliki spoza allowlisty.
4. Dodać GitHub Actions workflow uruchamiany przy PR do `bridge/work-bridge-bootstrap`, z uprawnieniami tylko `contents: read`. Walidator musi być pobrany z base commita, nie z PR head.
5. Dodać testy standardowej biblioteki Python dla dozwolonego READY, DRAFT, MASTER_DONE, braku Task ID/manifestu, złego base SHA, nieautoryzowanego pliku, złej nazwy brancha, brakującego raportu, PR z forka i błędnego JSON.
6. Raportować wymagane ustawienie branch protection/ruleset osobno. Sam workflow nie jest egzekwowany jako wymagany check, dopóki właściciel repo nie ustawi go jako required status check.

**Zakazy:** brak zmian kodu Android, Gradle, providerów, pamięci, main i branchy audit/*. Bez merge, bez APK, bez testów Android. Nie deklarować aktywnej branch protection bez potwierdzenia.

**Kryteria akceptacji:** testy skryptowe przechodzą; workflow jest syntaktycznie poprawny i ograniczony do read-only; raport wyraźnie rozróżnia check uruchamiany od checka wymaganego przez branch protection; zmienione są wyłącznie cztery dozwolone pliki.

**Instrukcja końcowa:** pozostaw branch WB-004 bez merge. MASTER niezależnie zweryfikuje diff.


---

## WB-005 — Rzeczywisty test CI Work Bridge przez tymczasowy PR

**Status:** DONE — CI smoke test WB-005 zweryfikowany przez MASTER; raport zawiera run 42427422527. PR #2 zamknięty bez merge.

**Cel:** Uruchomić nowy workflow GitHub Actions na rzeczywistym PR i potwierdzić automatycznie: policy unit tests, walidację Task ID, READY, Base SHA, raportu i allowlisty. PR jest wyłącznie kontrolnym smoke testem; po zebraniu wyników MASTER go zamknie bez merge.

**Base branch:** `bridge/work-bridge-bootstrap`

**Base commit:** HEAD tej gałęzi po opublikowaniu niniejszego zadania.

**Branch zadaniowy:** `bridge/task-WB-005-policy-ci-smoke`

**Dozwolone pliki (dokładna allowlista):**
- `AI_MASTER_BRIDGE/REPORTS/WB-005.md`

**Maszynowa autoryzacja WB_TASK_V1:**
<!-- WB_TASK_V1
{
  "id": "WB-005",
  "status": "DONE",
  "base_branch": "bridge/work-bridge-bootstrap",
  "allowed_paths": [
    "AI_MASTER_BRIDGE/REPORTS/WB-005.md"
  ]
}
-->

**Zakres dozwolony:**
1. MASTER utworzy branch WB-005 od dokładnego Base commit.
2. Doda wyłącznie raport `AI_MASTER_BRIDGE/REPORTS/WB-005.md`, zawierający linie `Task ID: WB-005` i `Base SHA: <pełny SHA>`.
3. MASTER utworzy Draft PR do `bridge/work-bridge-bootstrap` wyłącznie po to, aby uruchomić workflow `Work Bridge authorization`.
4. Poczeka na rzeczywisty wynik workflow i pobierze job/steps/logs.
5. Potwierdzi osobno wynik unit tests i walidacji PR; zapisać run ID, job ID, status i link.
6. Po zebraniu wyniku zamknąć PR bez merge. Nie zmieniać kodu ani innych plików.

**Zakazy:** nie mergeować PR; nie zmieniać branch protection/rulesets; nie dodawać plików poza allowlistą; nie wprowadzać zmian do aplikacji Android.

**Kryteria akceptacji:** PR uruchamia workflow; policy testy kończą się exit 0; walidator akceptuje WB-005 zgodnie z manifestem; diff PR zawiera tylko raport; PR zostaje zamknięty bez merge.

**Instrukcja końcowa:** zachowaj branch i raport jako audit evidence. MASTER independently verifies every result.

---

---
## WB-007 — Odtwarzalna ścieżka Android Studio → APK Debug → instalacja

**Status:** READY — P0 / najwyższy priorytet. Jawnie autoryzowane przez ChatGPT MASTER 2026-11-08.

**Cel:** Doprowadzić projekt do rzeczywistej, powtarzalnej ścieżki budowania APK, którą właściciel może uruchamiać i testować w Android Studio. Nie wystarczy sama dokumentacja ani kompilacja CI — wymagany jest rzeczywisty artefakt APK z udowodnionego builda.

**Repozytorium:** krzysztofchojnacki5-ship-it/aimaster_071_work
**Base branch:** audit/0.6.149-single-chat-snapshot
**Base SHA:** e37c3eded7be0ac8f92532e35cbb1e6f0dcb7eca
**Branch zadaniowy:** build/WB-007-android-apk-pipeline

**Dozwolone ścieżki:**
- gradle/wrapper/gradle-wrapper.jar
- gradle/wrapper/gradle-wrapper.properties
- gradlew
- gradlew.bat
- .github/workflows/android-audit.yml
- BUILD_AND_INSTALL_APK_PL.md
- BUILD_STATUS_0.6.150_PL.md
- PACKAGE_REGISTRY_PL.md

**Maszynowa autoryzacja WB_TASK_V1:**
<!-- WB_TASK_V1
{
  "id": "WB-007",
  "status": "READY",
  "base_branch": "audit/0.6.149-single-chat-snapshot",
  "allowed_paths": [
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
    "gradlew",
    "gradlew.bat",
    ".github/workflows/android-audit.yml",
    "BUILD_AND_INSTALL_APK_PL.md",
    "BUILD_STATUS_0.6.150_PL.md",
    "PACKAGE_REGISTRY_PL.md"
  ]
}
-->

**Zakres:**
1. Potwierdź dokładny base SHA i czyste drzewo. Zachowaj aktualne źródła aplikacji.
2. Zbadaj Gradle Wrapper: skrypty gradlew i gradlew.bat istnieją, ale w bazowej gałęzi nie znaleziono gradle/wrapper/gradle-wrapper.jar. Odtwórz go oficjalnym zadaniem Gradle Wrapper dla Gradle 8.13 albo z zaufanej dystrybucji Gradle 8.13. Nie pobieraj z nieznanych mirrorów.
3. Zapewnij uruchamianie z Windows przez gradlew.bat oraz Android Studio przez Gradle Wrapper. Zweryfikuj skrypt POSIX pod kątem praw wykonywania.
4. Utrzymaj kompatybilność: AGP 8.13.2, Kotlin 2.0.21, Gradle 8.13, JDK 17, compileSdk 36, minSdk 26.
5. Rozszerz istniejący workflow android-audit.yml tak, aby branch build/WB-007-android-apk-pipeline uruchamiał workflow przy push. Rozdziel testDebugUnitTest i assembleDebug na osobne kroki, aby build APK był próbą niezależną od wyniku testów. Publikuj app/build/outputs/apk/debug/app-debug.apk jako artefakt GitHub Actions przez upload-artifact. Nie osłabiaj testów ani uprawnień.
6. Dodaj BUILD_AND_INSTALL_APK_PL.md: klonowanie repo, checkout gałęzi, otwarcie katalogu w Android Studio, JDK 17, SDK 36, Gradle Sync, testDebugUnitTest, assembleDebug, lokalizacja APK, instalacja telefonu i typowe problemy.
7. Zapisz applicationId, debug applicationIdSuffix, versionName/versionCode i SHA-256 artefaktu. Obecny app/build.gradle.kts deklaruje versionName 0.6.54, versionCode 80, mimo że gałąź źródeł ma numer 0.6.149. Wyjaśnij rozbieżność; nie zmieniaj numeracji bez spójnego planu i aktualizacji rejestru.
8. Uruchom rzeczywiście ./gradlew --version, ./gradlew :app:testDebugUnitTest oraz ./gradlew :app:assembleDebug. Zapisz kody wyjścia, wersje JDK/Gradle/SDK, czas, ścieżkę APK, rozmiar i SHA-256. Brak SDK/sieci = NOT RUN/BLOCKED z przyczyną; nie zastępuj lokalnego builda wynikiem CI.
9. Uzupełnij BUILD_STATUS_0.6.150_PL.md i PACKAGE_REGISTRY_PL.md wyłącznie rzeczywistymi wynikami. PASS tylko jeśli APK istnieje i dowód builda jest zachowany.
10. Sprawdź diff, git diff --check, allowlistę i sekrety. Wypchnij wyłącznie branch zadaniowy. Bez PR/merge — MASTER wykona niezależny przegląd.

**Zakazy:** żadnych zmian Kotlin/Java/UI/agentów; bez release signing, kluczy, release APK, automatycznego merge, zmiany applicationId i Companion.

**Kryteria akceptacji:** wrapper działa z czystego checkoutu; Android Studio ma dokładną instrukcję; CI publikuje debug APK; testy i assembleDebug mają rzeczywiste wyniki; jeśli lokalny build jest możliwy, APK ma SHA-256 i można go zainstalować; zmiany tylko w allowliście.

---
## WB-008 — Agent Manager parity: centrum zarządzania agentami w AI MASTER PRO

**Status:** READY — P1 / po WB-007. Jawnie autoryzowane przez ChatGPT MASTER 2026-11-08.

**Cel:** Zbudować własne centrum zarządzania agentami o funkcjonalności porównywalnej z panelem Agent Manager w Android Studio/IDE. Nie kopiować kodu, binariów, prywatnych API ani zasobów Google/JetBrains. Odtworzyć obserwowalne funkcje i UX własną implementacją.

**Repozytorium:** krzysztofchojnacki5-ship-it/aimaster_071_work
**Base branch:** audit/0.6.149-single-chat-snapshot
**Base SHA:** e37c3eded7be0ac8f92532e35cbb1e6f0dcb7eca
**Branch zadaniowy:** feature/WB-008-agent-manager-parity

**Dozwolone ścieżki:**
- app/src/main/java/pl/aimasterpro/app/MainActivity.kt
- app/src/main/java/pl/aimasterpro/app/agents/AgentCatalog.kt
- app/src/main/java/pl/aimasterpro/app/agents/TeamRunEngine.kt
- app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt
- app/src/main/java/pl/aimasterpro/app/core/AgentManager*.kt
- app/src/test/java/pl/aimasterpro/app/agents/AgentManager*Test.kt
- app/src/test/java/pl/aimasterpro/app/core/AgentManager*Test.kt
- AGENT_MANAGER_PARITY_AUDIT_PL.md
- AI_MASTER_BRIDGE/REPORTS/WB-008.md

**Maszynowa autoryzacja WB_TASK_V1:**
<!-- WB_TASK_V1
{
  "id": "WB-008",
  "status": "READY",
  "base_branch": "audit/0.6.149-single-chat-snapshot",
  "allowed_paths": [
    "app/src/main/java/pl/aimasterpro/app/MainActivity.kt",
    "app/src/main/java/pl/aimasterpro/app/agents/AgentCatalog.kt",
    "app/src/main/java/pl/aimasterpro/app/agents/TeamRunEngine.kt",
    "app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt",
    "app/src/main/java/pl/aimasterpro/app/agents/AgentManagerModels.kt",
    "app/src/main/java/pl/aimasterpro/app/agents/AgentManagerStore.kt",
    "app/src/main/java/pl/aimasterpro/app/agents/AgentManagerScreen.kt",
    "app/src/test/java/pl/aimasterpro/app/agents/AgentManagerParityTest.kt",
    "AGENT_MANAGER_PARITY_AUDIT_PL.md",
    "AI_MASTER_BRIDGE/REPORTS/WB-008.md"
  ]
}
-->

**Zakres:**
1. Najpierw opisz na podstawie dostępnego UI/obserwacji agenta Android Studio funkcje panelu Agent Manager: lista agentów, stan queued/running/completed/failed/cancelled, zadania, aktywność, równoległość, logi/komunikacja, wykorzystanie kontekstu/tokenów, limity i zatrzymywanie/wznawianie. Odróżnij funkcje widoczne od danych, których IDE nie udostępnia.
2. Zmapuj istniejące w AI MASTER PRO: AgentCatalog, TeamRunEngine, ApiTokenTracker, ProviderQuotaScheduler, MasterWorkJournalStore, TaskCheckpointStore, DiagnosticStore i obecny UI aktywności. Nie duplikuj źródeł prawdy.
3. Zaprojektuj ekran Agent Manager z kartami agentów, statusem, zadaniem, providerem/modelem, tokenami/kosztami, limitem kontekstu wyłącznie jeśli dostawca go ujawnia, czasem pracy, błędami, heartbeat, logiem komunikacji i kontrolkami stop/pause/resume.
4. Dane wyłącznie z rzeczywistych zdarzeń/liczników. Nie wymyślaj tokenów, kontekstu, procentów ani kosztów. Brak danych = N/A.
5. MASTER ma widzieć agentów równoległych, postęp i statusy. Każdy agent ma executionId, taskId, chatId, provider/model.
6. Stop/pause/resume muszą wykonywać realne akcje powiązane z TeamRunEngine/checkpointami; kontrolki pozorne są niedozwolone.
7. Zachowaj budżet, darmowy routing, limity równoległości, redakcję sekretów, anulowanie i brak aktywacji Companion.
8. Najpierw audyt parytetu i model danych, potem mały vertical slice. Nie przepisuj całego MainActivity i nie twórz atrap produkcyjnych.
9. Dodaj testy stanów, agregacji usage, brakujących danych i przejść sterowania. Uruchom i zapisz wyniki. Android build po WB-007, jeśli środowisko gotowe.
10. Wypchnij wyłącznie branch zadaniowy. Bez PR/merge do niezależnego audytu MASTER.

**Kryteria akceptacji:** audyt porównawczy; jeden spójny model danych; widok realnej aktywności wielu agentów; prawdziwe kontrolki lub disabled; testy; zmiany tylko w allowliście.

**Priorytet:** WB-007 przed WB-008. WB-006 read-only poller odłożony — nie pobieraj go jako pierwszego zadania.
## WB-006 — Pierwszy zewnętrzny worker: read-only poller kolejki

**Status:** DEFERRED — jawnie autoryzowane przez ChatGPT MASTER 2026-11-08.

**Cel:** Zbudować pierwszy uruchamialny element przyszłego workera 24/7. W tej fazie program tylko odczytuje i waliduje kolejkę Work Bridge. Nie wykonuje zadań, nie wywołuje modeli i nie zapisuje niczego do GitHub. To celowo bezpieczny fundament przed dodaniem lease, wykonawcy i sandboxa.

**Decyzja architektoniczna:** ciągły wykonawca docelowo działa jako zewnętrzna usługa VPS/systemd, nie jako proces Android ani sesja IDE. Dokument: AUTONOMOUS_WORKER_ARCHITECTURE_0.6.150_PL.md, branch work/autonomous-worker-architecture-2026-11-08, commit 5c591d05ebca18c49746d09dfd0d0afa56804696.

**Base branch:** bridge/work-bridge-bootstrap

**Base commit:** HEAD tej gałęzi po opublikowaniu niniejszego zadania. Agent musi wykonać git fetch origin, zapisać dokładny SHA aktualnego origin/bridge/work-bridge-bootstrap i użyć go jako podstawy task brancha.

**Branch zadaniowy:** bridge/task-WB-006-readonly-poller

**Dozwolone pliki (dokładna allowlista):**
- tools/autonomous_worker/poller.py
- tools/autonomous_worker/test_poller.py
- tools/autonomous_worker/README.md
- AI_MASTER_BRIDGE/REPORTS/WB-006.md

**Maszynowa autoryzacja WB_TASK_V1:**
<!-- WB_TASK_V1
{
  "id": "WB-006",
  "status": "DEFERRED",
  "base_branch": "bridge/work-bridge-bootstrap",
  "allowed_paths": [
    "tools/autonomous_worker/poller.py",
    "tools/autonomous_worker/test_poller.py",
    "tools/autonomous_worker/README.md",
    "AI_MASTER_BRIDGE/REPORTS/WB-006.md"
  ]
}
-->

**Zakres dozwolony:**
1. Zbudować mały moduł Python oparty wyłącznie na bibliotece standardowej; bez zależności zewnętrznych.
2. Poller pobiera wyłącznie publiczny plik AI_MASTER_BRIDGE/INBOX.md z repozytorium krzysztofchojnacki5-ship-it/aimaster_071_work, branch bridge/work-bridge-bootstrap, przez HTTP GET. URL i timeout muszą być konfigurowalne. Nie używać tokenu GitHub; repo jest publiczne.
3. Dodać tryb jednorazowy --once i tryb ciągłego odczytu z domyślnym interwałem 30 s, minimalnym 5 s, backoffem dla błędów sieci/HTTP i poprawnym zakończeniem po Ctrl+C/SIGTERM.
4. Parsować bloki WB_TASK_V1 fail-closed: dokładnie jeden poprawny blok na zadanie; poprawny JSON; unikalne ID; status jako string; base_branch jako string; allowlista jako lista niepustych ścieżek względnych. Odrzucać duplikaty ID, duplikaty ścieżek, ścieżki absolutne, .., wildcardy, niepoprawne typy i malformed/duplikowane bloki.
5. Zwracać deterministyczny snapshot JSON: czas odczytu, SHA-256 pobranej treści INBOX, liczbę zadań READY oraz listę ID/status/base_branch/allowed_paths. Nie wypisywać pełnej treści INBOX ani żadnych sekretów.
6. Poller jest wyłącznie read-only: nie tworzy branchy, nie zmienia statusów, nie claimuje zadań, nie uruchamia kodu projektu, nie wywołuje modeli i nie wykonuje komend shell.
7. Testy offline przez wstrzyknięty fetch/mock transport; nie wykonywać realnych requestów sieciowych w testach.
8. Dodać dokumentację uruchomienia i jawnie opisać, że poller nie jest jeszcze workerem wykonującym zadania.
9. Uruchomić python3 -m py_compile tools/autonomous_worker/poller.py tools/autonomous_worker/test_poller.py oraz python3 -m unittest discover -s tools/autonomous_worker -p 'test_*.py' -v; zapisać rzeczywiste kody wyjścia i wyniki.
10. Sprawdzić git diff --check, listę plików i skan sekretów. Jeśli brak dedykowanego skanera, wpisać NOT VERIFIED.

**Zakazy:**
- Nie zmieniać żadnych plików Kotlin/Android, Gradle, manifestu, providerów ani pamięci aplikacji.
- Nie modyfikować INBOX, README Work Bridge, workflow ani walidatora WB-004.
- Nie tworzyć żadnych tokenów, sekretów ani kluczy API.
- Nie wykonywać modeli, testów projektu Android, builda APK, PR ani merge.
- Nie rozszerzać zakresu poza cztery dozwolone ścieżki.
- Nie deklarować PASS bez rzeczywistego uruchomienia poleceń i kodu wyjścia.

**Kryteria akceptacji:**
- Poller poprawnie pobiera INBOX i wyświetla READY tasks.
- Błędne/niejednoznaczne manifesty są odrzucane fail-closed.
- Testy jednostkowe offline przechodzą.
- Tryb ciągły ma ograniczony interwał/backoff i kończy się bez utraty kontroli.
- Zmiany ograniczone do allowlisty, branch zadaniowy od dokładnego base SHA, raport zawiera pełne SHA i wyniki.
- Żadnego uruchamiania zadań i żadnych zapisów do GitHub.

**Instrukcja końcowa:** wypchnij wyłącznie branch zadaniowy po weryfikacji diffu. Nie twórz PR ani merge. ChatGPT MASTER niezależnie zweryfikuje branch i raport.
