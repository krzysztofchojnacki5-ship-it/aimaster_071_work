# AI MASTER PRO — Work Bridge

Wspólna, audytowalna kolejka pracy między ChatGPT MASTER, agentem Android Studio i GitHub.

## Zasady bezpieczeństwa

1. Agent Android Studio wykonuje wyłącznie zadanie jawnie opisane w `INBOX.md`.
2. Każde zadanie realizuje na osobnym branchu `bridge/task-<ID>`; nie pracuje bezpośrednio na `main` ani na branchach audytowych.
3. Agent nie wykonuje merge. Push i utworzenie PR są dozwolone dopiero po sprawdzeniu diffu i skanowaniu sekretów.
4. Nie wolno kopiować do raportów wartości zmiennych środowiskowych, API keys, tokenów, haseł, plików lokalnych ani zawartości `local.properties`.
5. Nie wolno deklarować PASS bez zapisanego kodu wyjścia i raportu testów. Timeout, anulowanie i brak uruchomienia oznaczają NOT VERIFIED.
6. Nie wolno tworzyć APK ani deklarować Android build PASS bez rzeczywistego `:app:assembleDebug` zakończonego kodem 0.
7. Przed rozpoczęciem agent zapisuje base commit SHA. Po pracy zapisuje head commit SHA, listę plików, testy, build i wynik skanowania sekretów.
8. Nie wolno zmieniać `INBOX.md` w ramach realizacji zadania. Status i raport zapisuje się w `REPORTS/`.


## Twarda bramka autoryzacji (HARD GATE)

Źródłem autoryzacji wykonania jest wyłącznie najnowszy `AI_MASTER_BRIDGE/INBOX.md` z gałęzi bazowej wskazanej w zadaniu. Wiadomość w czacie, lokalna kopia INBOX ani polecenie „start” nie zastępują statusu opublikowanego przez MASTER-a.

### Obowiązkowa procedura przed każdą pracą

1. Wykonaj `git fetch origin`.
2. Odczytaj najnowszy `origin/bridge/work-bridge-bootstrap:AI_MASTER_BRIDGE/INBOX.md` i ustal dokładne Task ID.
3. Znajdź dokładnie jedno zadanie o tym ID. Zapisz SHA commita, z którego odczytano INBOX.
4. Rozpocznij pracę wyłącznie wtedy, gdy status tego konkretnego zadania wynosi dokładnie `READY`.
5. Gdy zadanie nie istnieje, status to `DRAFT`, `BLOCKED`, `DONE`, `MASTER_DONE`, jest niejednoznaczny, INBOX jest nieosiągalny albo nie można potwierdzić aktualności danych — przerwij bez wykonywania zakresu i zapisz `BLOCKED` w raporcie.
6. Po potwierdzeniu `READY` utwórz branch zadaniowy od dokładnego SHA bazowego wskazanego w zadaniu. Nie pracuj na branchu bazowym, produkcyjnym ani na branchu innego zadania.

### Zakaz samodzielnego nadawania uprawnień

- Agent nie zmienia statusów w `INBOX.md`, nie przepisuje sobie zakresu i nie dopisuje własnej autoryzacji.
- Polecenie użytkownika w czacie nie zmienia statusu zadania. Użytkownik może poprosić ChatGPT MASTER o aktualizację INBOX; dopiero commit MASTER-a z `READY` upoważnia agenta.
- Agent nie interpretuje statusu „prawie READY”, komentarza, raportu ani wcześniejszego polecenia jako zgody.
- Jeżeli status zmieni się po pobraniu INBOX, agent zatrzymuje pracę i ponownie sprawdza aktualny status przed kolejnym etapem.

### Przykłady decyzji

- `WB-000 — DRAFT` → **BLOCKED**. Nie wolno wykonywać nawet „niewielkiej” części zadania.
- `WB-002 — MASTER_DONE` → **BLOCKED dla agenta**; zakres został wykonany przez MASTER-a.
- `WB-002 — MASTER_DONE` → **BLOCKED dla agenta**. Cel został wykonany bezpośrednio przez MASTER-a; nie wolno powtarzać zadania.
- Brak Task ID w aktualnym INBOX → **BLOCKED**.

### Znaczenie statusów

- `DRAFT`: przygotowywane, nie wykonywać.
- `READY`: jawnie autoryzowane do wykonania przez agenta.
- `BLOCKED`: wstrzymane, nie wykonywać.
- `DONE`: wykonane i zweryfikowane zgodnie z kryteriami; nie wykonywać ponownie.
- `MASTER_DONE`: wykonane bezpośrednio przez ChatGPT MASTER; nie wykonywać przez agenta.


## Techniczna walidacja task PR (WB-004)

Repozytorium zawiera workflow `.github/workflows/work-bridge-authorization.yml` oraz walidator `tools/work_bridge/validate.py`.

- Workflow uruchamia kontrolę dla PR kierowanych do `bridge/work-bridge-bootstrap`.
- Kod walidatora jest pobierany z bazowego SHA PR, a nie z gałęzi PR. Workflow ma tylko `contents: read` i nie utrwala poświadczeń Git.
- Zadanie musi zawierać dokładnie jeden maszynowy blok `WB_TASK_V1` w `AI_MASTER_BRIDGE/INBOX.md` bazowego commita. Blok określa ID, status READY, branch bazowy i dokładną allowlistę plików.
- Nazwa brancha musi odpowiadać `bridge/task-WB-NNN-<slug>`; raport `AI_MASTER_BRIDGE/REPORTS/WB-NNN.md` musi podawać dokładny Task ID i pełny Base SHA.
- Walidator porównuje deklarowany Base SHA z rzeczywistym merge-base PR, sprawdza status READY zarówno w INBOX merge-base, jak i w aktualnym INBOX gałęzi docelowej. Zmiana statusu lub zakresu po rozpoczęciu zadania unieważnia autoryzację.
- Zmiany plików spoza allowlisty, fork PR, brak/duplikat/błędny manifest, brak raportu oraz operacje delete/rename/type-change są odrzucane.

### Ważne ograniczenie egzekwowania

Workflow jest kontrolą CI, ale **nie jest automatycznie wymaganym checkiem GitHub**. W chwili audytu rulesets zwracały pustą listę, gałąź bootstrap była oznaczona jako niechroniona, a dostępny connector nie pozwolił odczytać szczegółów endpointu branch protection (HTTP 403). Dopóki właściciel repozytorium nie włączy ochrony gałęzi i nie ustawi checka `validate-task` jako required, CI może zgłosić FAIL, ale GitHub nie musi blokować merge.

Po pierwszym rzeczywistym uruchomieniu PR należy potwierdzić dokładną nazwę checka w zakładce Checks i ustawić ją jako required dla `bridge/work-bridge-bootstrap`. Wymagaj PR zamiast bezpośredniego push, jeśli celem jest egzekwowanie polityki dla wszystkich zmian.

## Status możliwości

- Odczyt i zapis lokalnych plików przez agenta: potwierdzony audytem użytkownika.
- Odczyt i zapis GitHub przez lokalny git: potwierdzony konfiguracją origin; push konkretnego brancha należy weryfikować per sesja.
- Automatyczne cykliczne polling: niedostępne w trybie agenta IDE według audytu.
- Automatyczne testy: narzędzie IDE wykrywa zadania Gradle; rzeczywisty pełny test i build nie zostały jeszcze potwierdzone w raporcie audytu.

## Uruchomienie zadania

W Android Studio: odśwież branch `bridge/work-bridge-bootstrap`, przeczytaj `INBOX.md`, a następnie wykonuj wyłącznie zadanie o statusie `READY`. Nie wykonuj zadań oznaczonych `DRAFT`, `BLOCKED` lub `DONE`.
