# Kontrakt hostowego rejestru wywołań narzędzi — 0.6.91

## Lifecycle

1. Host adapter wywołuje `ToolExecutionRegistry.begin(taskId, chatId, snapshotId, kind, toolName)`.
2. Registry generuje executionId samodzielnie i zapisuje rekord NOT_RUN do trwałego evidence ledgeru.
3. Dopiero po potwierdzonym zapisie adapter może rozpocząć operację.
4. Po rzeczywistym zakończeniu adapter przekazuje PASS lub FAIL do `complete(handle, ...)`.
5. Jeśli operacja została anulowana/przerwana bez wyniku, adapter wywołuje `abandon(handle, reason)`.
6. MASTER otrzymuje wynik wyłącznie wtedy, gdy completion został trwale zapisany i później pobrany przez exact-scope resolver.

## Zasady bezpieczeństwa

- executionId jest host-generated; model nie może go podać ani wybrać.
- Handle jest związany z instancją registry i może zostać zużyty tylko raz.
- Foreign handle, replay i double completion są odrzucane.
- Awaria zapisu startu blokuje uruchomienie narzędzia.
- Awaria zapisu completion pozostawia NOT_RUN; nie wolno pokazywać PASS.
- Abandon nie jest PASS.
- Status PASS oznacza wyłącznie wynik konkretnego adaptera; nie może być mapowany między kategoriami. Odpowiedź HTTP PASS nie jest testem jednostkowym, a test Kotlin/JVM PASS nie jest Android build PASS.
- Registry nie wykonuje narzędzi samodzielnie i nie tworzy sandboxa. Każdy adapter musi mieć własne limity i jawny kontrakt wyniku.

## Granica wdrożenia 0.6.91

Prymityw i testy są w source set. Nie ma jeszcze podłączonych adapterów, więc żadne istniejące wywołanie dostawcy/modelu nie jest automatycznie raportowane jako TOOL_EXECUTION.

## Integracja adaptera — 0.6.92
- `EvidenceTrackedToolAdapter` jest wzorcem dla hostowych call sites: registry begin przed operacją, completion po jej wyniku.
- PASS adaptera oznacza tylko, że hostowa funkcja zwróciła wynik. Wynik domenowy (np. `ProjectTestRunner.PROJECT_TESTS=NOT_RUN`) pozostaje osobnym dowodem i nie może zostać nadpisany przez PASS adaptera.
- Wyjątek → TOOL_EXECUTION/FAIL; anulowanie/przerwanie → NOT_RUN; błąd zapisu completion → pozostaje wcześniej zapisany NOT_RUN.
- Pierwsze podłączenie: `MainActivity` / `project.workspace.verify`. To nie jest jeszcze globalne pokrycie wszystkich narzędzi.
- Rekordy legacy bez chatId nie są sztucznie przypinane do rozmowy; pozostają poza exact-scope TOOL_EXECUTION.
