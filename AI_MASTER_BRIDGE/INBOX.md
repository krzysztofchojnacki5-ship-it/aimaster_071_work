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
