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

**Status:** READY — zadanie jawnie opublikowane przez ChatGPT MASTER.

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