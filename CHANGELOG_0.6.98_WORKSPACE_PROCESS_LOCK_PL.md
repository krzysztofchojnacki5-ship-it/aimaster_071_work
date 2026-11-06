# AI MASTER PRO 0.6.98-SOURCE — process-wide workspace lock

- Baza 0.6.97-SOURCE; aplikacja nadal 0.6.54 / versionCode 80.
- Dodano fair, reentrant `ProjectWorkspaceOperationLock` dla wszystkich hostowych operacji na prywatnym workspace.
- Import, patch apply, analiza/preview, weryfikacja, rollback, manifest identity i odczyty źródeł są koordynowane przez wspólny lock zamiast osobnych monitorów poszczególnych instancji.
- Aktywacja ChangeSet sprawdza digest bieżącego manifestu i zapisuje active pointer pod tą samą blokadą.
- UI używa nieblokującego `tryWithLock`; zajęty workspace daje komunikat/stan busy zamiast czekać na długą operację w tle.
- Testy locka: mutual exclusion, reentrancy, non-blocking probe; resolver harness 68/68 PASS. ChangeSet compile shim `kotlinc -Werror` PASS.
- Gradle/Robolectric/Android build/APK/device NOT RUN; Companion transport pozostaje BLOCKED.
