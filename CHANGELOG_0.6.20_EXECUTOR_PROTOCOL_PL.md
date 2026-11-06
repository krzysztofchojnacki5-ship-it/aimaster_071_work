# AI MASTER PRO 0.6.20 — kontrakt lokalnego executora

## Cel

Przygotować bezpieczny, wersjonowany protokół dla przyszłego companion executora desktopowego. Ta wersja definiuje dane i walidację dowodów; **nie uruchamia kodu projektu i nie udaje, że sandbox już istnieje**.

## Zmiany

- Dodano `ExecutorProtocol` z wersjonowanymi modelami `BundleManifest`, `ExecutionRequest`, `ResourcePolicy` i `ExecutionReceipt`.
- Manifest wymaga deterministycznego porządku plików, SHA-256 każdego pliku, zgodnej sumy rozmiarów, unikalnych ścieżek i digestu całego manifestu.
- Zdefiniowano limity: 3 000 plików, 8 MiB na plik, 40 MiB łącznie, 15 minut, 2 048 MiB RAM i maksymalnie 4 rdzenie CPU.
- Sieć jest domyślnie i obowiązkowo wyłączona. Żądanie z `networkAllowed=true` jest odrzucane.
- Lista zadań jest typowana i zamknięta: `UNIT_TEST_DEBUG` oraz `COMPILE_DEBUG`. Protokół nie przyjmuje dowolnej komendy, ścieżki wykonywalnej ani argumentów powłoki.
- Walidator receipt wiąże wynik z `runId`, digestem manifestu i konkretnym zadaniem; sprawdza czas, kod wyjścia, deklarowane limity, sandbox, wyłączenie sieci i podpis `SHA256withRSA` z przypiętym kluczem RSA co najmniej 2048 bitów.
- Rozszerzono odrzucanie ścieżek lokalnych/sekretów, traversal, ścieżek Windows i znaków sterujących.
- Dodano testy regresyjne protokołu.

## Granice zaufania — ważne

- Podpis receipt potwierdza, że raport podpisał posiadacz przypiętego klucza. **Nie jest sam w sobie dowodem**, że OS faktycznie odizolował proces. Companion musi być zaufaną, audytowaną implementacją, która podpisuje receipt dopiero po uruchomieniu zadania w realnym sandboxie i potwierdzeniu wymuszonych limitów.
- Executor musi ponownie zweryfikować digest manifestu, wszystkie SHA-256 i rozmiary plików po stronie desktopu. Nie może ufać samemu opisowi bundle.
- Kod projektu, skrypty Gradle i wrappery pozostają nieufne. Executor ma używać własnego, kontrolowanego runtime Gradle, a kod budowania wykonywać w OS-level sandboxie (np. izolowany kontener/rootless runtime) z ograniczeniami CPU, RAM, czasu, procesów, systemu plików i sieci.
- Sekretów nie wolno wysyłać do usług zewnętrznych. Bundle musi być przygotowany lokalnie, z filtrem ścieżek i skanem treści; desktop ponawia skan przed wykonaniem.
- W tej wersji nie ma jeszcze generatora archiwum bundle, companion desktopowego, konfiguracji przypiętego klucza produkcyjnego, UI połączenia ani egzekwowania sandboxa.
- `ChangeSetStore` nie otrzymuje publicznej metody zapisu `PROJECT_TESTS`. `APPLIED` nadal jest zablokowane; obecny runner pozostawia `PROJECT_TESTS = NOT_RUN`.
- Istniejący peer review przez `TeamRunEngine` pozostaje bez zmian i nie jest dublowany.

## Weryfikacja

- `ExecutorProtocol.kt`: kompilacja Kotlin/JVM PASS.
- `ExecutorProtocolTest`: 5/5 PASS w lokalnym harnessie JVM ze stubem JUnit 4.
- Pełny Gradle/APK, Android UI i Robolectric: NOT RUN (środowisko nie ma skonfigurowanego Android SDK/dystrybucji Gradle).
