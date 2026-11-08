# Build status — 0.6.83-SOURCE

- Parent: `AI_MASTER_PRO_0_6_82_COMPANION_DIRECTORY_FSYNC_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: wprowadzić deterministyczne punkty fault injection w rzeczywistej ścieżce operacji markerów Companion i opisać granice awarii publikacji/usuwania.

## Zmiany produkcyjne

- `CompanionProvisioningStore` otrzymał wewnętrzny, domyślnie pusty `faultInjector`; produkcja nie zmienia zachowania, dopóki test jawnie nie ustawi callbacku.
- Punkty kontrolne obejmują: commit markeru preferences, zapis pinów, atomowy `Files.move`, fsync katalogu po publikacji, unlink markera, fsync katalogu po usunięciu oraz próbę usunięcia aliasu metadata w Android Keystore.
- Dla `wipeAllTrust()` oznaczono granice przed/po commicie, przed/po usunięciu plikowego markera i przed/po usunięciu aliasu Keystore.
- Punkty `before` pozwalają symulować przerwanie przed operacją, a `after` — awarię/utracenie procesu po jej wykonaniu, ale przed dalszym krokiem. Są to punkty wstrzykiwania wyjątków, nie pełna abstrakcja adaptera I/O.

## Testy dodane

- `CompanionTrustPersistentLockTest.injectedFailuresExposeAtomicMarkerCrashBoundaries()` — sprawdza cztery granice publikacji: przed rename, po rename, przed fsync katalogu i po fsync.
- `CompanionTrustPersistentLockTest.injectedDeleteFailuresDistinguishUnlinkFromDirectoryDurability()` — sprawdza cztery granice usuwania: przed unlink, po unlink, przed fsync katalogu i po fsync.
- Testy Robolectric są ADDED / NOT RUN. Nie raportujemy ich jako PASS.

## Walidacja wykonana

- Hostowy harness `tools/companion_fault_matrix_harness.kt` skompilowany przez `kotlinc -Werror` i uruchomiony na JDK 21/Linux.
- Wynik: **200/200 przypadków wstrzykniętej awarii PASS** (25 powtórzeń × 4 punkty publikacji + 25 × 4 punkty usuwania).
- Harness odtwarza kolejność operacji NIO i weryfikuje stan markera/temp-file po awarii. Nie wykonuje kodu Android `CompanionProvisioningStore`; jest niezależną walidacją protokołu plikowego.
- Statyczna kontrola obecności punktów fault injection i testów: PASS.
- Gradle/JUnit/Robolectric NOT RUN; Android Keystore NOT RUN; `assembleDebug`/APK/device NOT RUN.

## Luki i ograniczenia — jawnie otwarte

- Obecny seam wstrzykuje wyjątki na granicach operacji; **nie podmienia** `SharedPreferences`, `Files` ani `KeyStore`. Nie symuluje bezpośrednio `SharedPreferences.commit() == false` ani wewnętrznego wyjątku z `KeyStore.deleteEntry()` — do tego potrzebny jest następny, wstrzykiwalny adapter persistence/Keystore.
- Crash matrix obejmuje obecnie granice markerów plikowych oraz punkty wipe, ale nie jest jeszcze pełnym testem restartu Android/Robolectric po każdej możliwej awarii.
- Synchronizacja `synchronized(this)` jest per instancja store. Produkcyjny graph używa application-scoped store w jednym procesie; nie deklarujemy obsługi wielu procesów ani gwarancji serializacji mutacji przez arbitralnie tworzone instancje.
- Weryfikacja na urządzeniu/OEM filesystemie pozostaje wymagana, szczególnie dla `FileChannel.force(true)` na katalogu.
- Nadal obowiązuje granica 0.6.80: jednoczesna awaria obu markerów trwałych i usunięcia aliasu Keystore nie gwarantuje fail-closed po restarcie.
- Transport Companion pozostaje nieaktywny / BLOCKED.

## Następny etap

Wydzielić rzeczywiste adaptery `SharedPreferences` / marker file operations / Keystore deletion, aby test mógł wymusić `commit() == false`, błąd `deleteEntry()` i odczyt stanu po odtworzeniu nowej instancji. Następnie wykonać macierz promote/revoke/wipe oraz sprawdzić współbieżność i granicę single-process.
