# AI MASTER PRO 0.6.19 — weryfikacja workspace i rollback

## Nowe elementy
- `ProjectWorkspaceStore.verifySnapshot()` kontroluje integralność kopii zapasowej względem jej manifestu SHA-256.
- `ProjectWorkspaceStore.restoreSnapshot()` przywraca wyłącznie zweryfikowany snapshot: staging → ponowna walidacja → transakcyjne przełączenie.
- Zdrowy workspace przed przywróceniem otrzymuje dodatkowy snapshot bezpieczeństwa. Uszkodzony workspace nie jest traktowany jako poprawna kopia.
- `ProjectTestRunner` porównuje manifest bieżącego projektu z snapshotem i weryfikuje, czy zmienione ścieżki dokładnie odpowiadają zapisowi w ChangeSet.
- Centrum dowodzenia udostępnia akcję weryfikacji patcha. Wykryty FAIL uruchamia rollback do snapshotu; po udanym przywróceniu zmiana otrzymuje `ROLLED_BACK`.
- `ChangeSetStore` zapisuje poziom weryfikacji. `APPLIED` wymaga `PASS` na poziomie `PROJECT_TESTS`; PASS integralności workspace nie wystarcza.

## Bezpieczeństwo i statusy
- Importowane skrypty Gradle, shell, testy i binaria nadal nie są wykonywane.
- Aplikacja nie ma jeszcze skonfigurowanego OS-level sandboxa procesu, który egzekwowałby limity CPU, RAM, czasu i sieci. Wykonanie kompilacji/testów projektu jest więc uczciwie raportowane jako `NOT_RUN`.
- Jeśli integralność workspace, snapshotu lub zakresu patcha zawiedzie, runner raportuje `FAIL` i podejmuje próbę rollbacku.
- Jeśli snapshot jest uszkodzony albo rollback się nie powiedzie, aplikacja nie udaje sukcesu; zmiana nie otrzymuje statusu `ROLLED_BACK`.

## Weryfikacja
- Testy czystych komponentów Kotlin/JVM uruchomione lokalnym runnerem z lekkim stubem JUnit 4.
- Zakres testów: parser patchy, import i integralność workspace, crash recovery, stosowanie patcha, odtwarzanie snapshotu, odrzucanie uszkodzonego snapshotu i zachowanie `NOT_RUN` runnera.
- Wynik czystych komponentów Kotlin/JVM: **38/38 PASS, 0 FAIL** (lokalny runner ze stubem JUnit 4).
- `ChangeSetStore.kt` skompilowany składniowo z lekkimi stubami Android/JSON; testy Robolectric nie zostały uruchomione.
- Pełny Gradle/APK i Android UI: NOT RUN — brak Android SDK, a pobranie Gradle 8.13 nie powiodło się z powodu `UnknownHostException` dla `services.gradle.org`.
