# CHANGELOG 0.6.116 — walidacja wejściowego receipt przed recovery

- `PatchReceiptRecoveryCoordinator.reconcile()` waliduje kompletność trwałego receipt przed wywołaniem backendu.
- Puste identyfikatory transakcji, ChangeSet, czatu, snapshotu lub workspace są odrzucane fail-closed.
- Sprzeczne flagi `rolledBack` i `rollbackRequested` są odrzucane przed operacją rollback, odczytem snapshotu i ledgeru.
- Dwa nowe testy negatywne potwierdzają brak jakiegokolwiek backend I/O dla niepoprawnego receipt.
- Dodano `tools/android_test_environment_check.sh` do powtarzalnego raportowania brakujących komponentów Gradle/Android.
- Coordinator 17/17, ChangeSet storage fault 10/10, recovery policy 11/11, receipt protocol 8/8, concurrency 3/3 + static guard, commit gate 7/7, workspace lock 5/5; razem 61 hostowych przypadków PASS.
- ChangeSet/HostActive compile shim `kotlinc -Werror` PASS.
- Filesystem integration i verification resolver TIMEOUT. Gradle/Robolectric/Android/APK/device NOT RUN.
- Preflight potwierdza brak wrapper JAR, systemowego Gradle i Android SDK oraz nierozwiązywalny DNS `services.gradle.org`.
- App pozostaje `0.6.54` / code `80`; Companion transport BLOCKED.
