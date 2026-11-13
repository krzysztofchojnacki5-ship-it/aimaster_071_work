# CHANGELOG — AI MASTER PRO 0.6.125-SOURCE

## ProjectTestRunner: własna granica wspólnej blokady workspace

- Publiczna metoda `ProjectTestRunner.verifyAppliedChange()` pobiera teraz `ProjectWorkspaceOperationLock.withLock` przed pierwszym odczytem workspace.
- Właściwa weryfikacja została przeniesiona do prywatnej metody `verifyAppliedChangeLocked()`. Odczyt integralności bieżącego workspace, snapshotu i listy plików odbywa się w jednej sekcji krytycznej.
- Dotychczasowy caller w MainActivity już trzyma blokadę; reentrantność `ReentrantLock` pozwala bezpiecznie wejść ponownie. Nowi callerzy nie mogą przypadkowo ominąć globalnej serializacji.
- Dodano JUnit concurrency test `runnerAcquiresWorkspaceGateWhenCalledOutsideMainActivity`: weryfikator powinien czekać na zwolnienie blokady przez inny wątek. Test ADDED / NOT RUN.
- `workspace_operation_lock_harness`: dodatkowy test reentrantnego `tryWithLock`, teraz 8/8 PASS.
- Dodano `SourceGuard.py`, który pilnuje, aby publiczna metoda runnera zdobywała blokadę i delegowała do prywatnej implementacji.

## Walidacja

- Hostowe przypadki PASS: 173.
- ChangeSet/HostActive compile shim z `-Werror`: PASS.
- ProjectTestRunner public-boundary source guard: PASS.
- MainActivity static lock-order/atomicity guards: PASS.
- Android preflight BLOCKED: brak Gradle wrapper JAR, systemowego Gradle i Android SDK; `services.gradle.org` nie rozwiązuje się przez DNS.
- Gradle/JUnit/Robolectric/Android build/APK/device/process-death: NOT RUN.
- App version: `0.6.54 / code 80`; Companion Transport: BLOCKED.
