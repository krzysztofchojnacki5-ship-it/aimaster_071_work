# AI MASTER PRO 0.6.101-SOURCE — trwały dziennik transakcji workspace

## Ryzyko wykryte w 0.6.100
Dotychczasowy `recoverInterruptedSwap()` wnioskował o stanie operacji wyłącznie z obecności katalogów `current/` i `.previous-*`. Nie miał trwałego zapisu zamiaru ani identyfikatora oczekiwanego manifestu. Przy awarii procesu pomiędzy rename katalogu i manifestu można było odtworzyć poprzedni projekt, ale nie dało się jednoznacznie rozstrzygnąć wszystkich stanów pośrednich ani odróżnić kompletnego commit od przypadkowej pary plików.

## Zmiany
- Dodano `workspace.swap.journal` z wersjonowanym, walidowanym rekordem: UUID transakcji, informacja o poprzednim workspace i SHA-256 oczekiwanego manifestu.
- Dziennik jest zapisywany do unikalnego pliku tymczasowego, `flush()` + `FileDescriptor.sync()`, a następnie publikowany przez `ATOMIC_MOVE` przed pierwszym rename katalogów. Jeśli system plików nie obsługuje atomowego zapisu dziennika, operacja nie rozpoczyna podmiany.
- `replaceCurrent()` uznaje commit dopiero po instalacji manifestu, zgodności jego SHA-256 i pełnej weryfikacji plików względem manifestu.
- Recovery przy starcie: kompletny `current/` zgodny z oczekiwanym hashem jest zatwierdzany i sprzątany; niekompletny swap odtwarza poprzednią parę; transakcja pierwszego importu bez poprzedniego projektu usuwa niekompletny wynik.
- Dziennik uszkodzony, nieprawidłowy, będący symlinkiem lub wskazujący poza root blokuje dostęp fail-closed; nie ma zgadywania ani czyszczenia danych.
- Zachowano kompatybilną ścieżkę odzyskiwania pozostałości po starszych wersjach bez dziennika.
- Po rozliczeniu transakcji usuwane są pozostałości stagingu importu, patcha i recovery.

## Testy lokalne
- Nowy `tools/workspace_transaction_recovery_harness/run.sh`: **4/4 PASS** — awaria po samym zapisie intencji, awaria w środku swapu i rollback, pełny commit przed cleanupem, uszkodzony dziennik fail-closed.
- Workspace core `kotlinc -Werror`: PASS.
- Resolver/lock harness: **73/73 PASS**.
- ChangeSet/HostActive compile shim: **PASS** (`kotlinc -Werror`).
- Gradle/JUnit/Robolectric/pełny Android build/APK/device: NOT RUN.

## Granice
Dziennik chroni atomową podmianę katalogu i manifestu workspace. Nie jest jeszcze dziennikiem transakcyjnym obejmującym wspólnie filesystem, ChangeSetStore i HostActiveChangeSetStore. Odzyskiwanie stanu ledgeru po śmierci procesu między apply a `recordSnapshot/status/active pointer` pozostaje kolejnym etapem. Companion transport nadal BLOCKED.
