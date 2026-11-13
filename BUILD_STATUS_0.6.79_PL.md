# Build status — 0.6.79-SOURCE

- Parent: `AI_MASTER_PRO_0_6_78_COMPANION_PIN_ROTATION_REVOCATION_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`.
- Audyt wykrył fail-open po błędzie trwałego zapisu trust: odcięcie aktywnych sesji nie usuwało starego ciphertextu, więc nowe handshakes mogły nadal zaakceptować stary pin.
- `CompanionProvisioningStore` ma teraz process-local trust latch po nieudanym destrukcyjnym mutation oraz best-effort usunięcie aliasu metadata AES z Android Keystore. Po udanym `wipeAllTrust()` latch jest resetowany. Jeżeli usunięcie aliasu zawiedzie, wyjątek jest zachowany jako suppressed i nie deklarujemy trwałego fail-closed po restarcie.
- `promotePeerRotation()` sprawdza istnienie peera i staged pin przed uruchomieniem fence; błąd precondition nie wywołuje globalnego odcięcia.
- Dodano `CompanionTrustRotationConcurrencyTest` (3 testy): registration-vs-revoke, global wipe-vs-concurrent admissions, izolacja innych peerów.

## Executed checks
- `kotlinc -Werror` dla `CompanionConnectionRegistry`, `CompanionPeerPinSet`, `CompanionSpkiSha256Pin` i wybranych testów: PASS.
- `CompanionProvisioningStore.kt` skompilowany `kotlinc -Werror` z lekkimi atrapami Android/JSON/core API: seam syntax/type-check PASS; nie jest to kompilacja z prawdziwym Android SDK/Keystore.
- Local shim-JUnit: 33/33 testy PASS na uruchomienie; 5 powtórzeń = 165/165 wywołań testów.
- Test registration-vs-revoke wykonuje 100 wyścigów na każde uruchomienie; 500 iteracji łącznie.
- ZIP integrity: PASS (po spakowaniu).

## Not executed
- Pełny compile Android `CompanionProvisioningStore.kt` z realnym Android SDK: NOT RUN.
- Gradle/JUnit/Robolectric/testDebugUnitTest: NOT RUN.
- Android Keystore failure injection i test restartu procesu: NOT RUN.
- `assembleDebug`, APK i testy fizycznego urządzenia: NOT RUN.
- Nie uruchomiono listenera ani transportu Companion.
