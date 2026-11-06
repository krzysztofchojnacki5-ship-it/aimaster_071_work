# CHANGELOG 0.6.117 — workspace identity fence dla recovery

## Zmiana produkcyjna
- `PatchReceiptRecoveryCoordinator.reconcile()` odczytuje bieżącą tożsamość workspace przed odczytem ledgeru.
- Recovery jest dopuszczane wyłącznie, gdy bieżąca tożsamość jest dokładnie równa `beforeWorkspaceIdentity` albo `workspaceIdentity` z receipt.
- Trzecia, obca tożsamość zatrzymuje recovery przed `readLedger`, `recordSnapshot`, `transition` i `invalidateActiveScope`. Zapobiega to uzgadnianiu starego receipt z workspace zastąpionym przez niezależny import/restore.
- Tożsamość workspace jest odczytywana raz i ta sama zweryfikowana wartość trafia do polityki recovery.

## Testy tej iteracji
- Coordinator: 18/18 PASS, w tym nowy test obcej tożsamości workspace blokującej dostęp do ledgeru.
- Policy: 11/11 PASS.
- Protocol: 8/8 PASS.
- Recovery concurrency: 3/3 PASS + MainActivity lock-order guard PASS.
- Workspace operation lock: 5/5 PASS.
- ChangeSet storage fault matrix: 10/10 PASS.
- Łącznie: 55 jawnych przypadków hostowych PASS; coordinator skompilowany `kotlinc -Werror`.
- Commit gate harness: TIMEOUT w bieżącej iteracji — nie zaliczono.

## Ograniczenia
- Filesystem integration / resolver: NOT RUN w tej iteracji.
- Gradle, Robolectric, prawdziwe SharedPreferences, Android build, APK i test urządzeniowy: NOT RUN.
- Preflight 0.6.116 wykazał brak wrapper JAR, systemowego Gradle i Android SDK oraz niedostępny DNS `services.gradle.org`; nie twierdzimy, że środowisko Android zostało odtworzone.
- App version `0.6.54`, versionCode `80`; Companion transport BLOCKED.
