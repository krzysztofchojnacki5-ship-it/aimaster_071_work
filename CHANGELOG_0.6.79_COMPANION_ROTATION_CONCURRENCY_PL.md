# 0.6.79-SOURCE — audyt wyścigów rotacji zaufania

## Najważniejsze ustalenie
Audyt ścieżek `promotePeerRotation()`, `revokePeer()` i `wipeAllTrust()` ujawnił przypadek fail-open po nieudanym trwałym zapisie: fence odcinał już aktywne sesje, ale stary zaszyfrowany pin mógł pozostać w SharedPreferences i zostać użyty przy nowym handshake.

## Zmiany
- Destrukcyjne operacje trust, które nie mogą potwierdzić commit lub przygotowania fence, ustawiają procesową blokadę odczytu zaufania.
- W tej samej sekcji krytycznej podejmowana jest próba usunięcia aliasu Android Keystore szyfrującego rekordy pinów. Jeżeli usunięcie powiedzie się, pozostawiony ciphertext nie może zostać odszyfrowany po restarcie procesu; wymagane jest jawne odzyskanie/re-pairing.
- Jeżeli usunięcie aliasu również zawiedzie, błąd jest dołączany jako suppressed do pierwotnego błędu, a blokada procesu nadal obowiązuje. Nie raportujemy wtedy gwarancji trwałego fail-closed po restarcie.
- `wipeAllTrust()` po potwierdzonym usunięciu payloadu resetuje blokadę procesu, aby jawna procedura recovery mogła rozpocząć nowy pusty epoch zaufania.
- `promotePeerRotation()` waliduje preconditions (istnienie peera i staged pin) przed fence; błędne wywołanie użytkownika nie unieważnia bez potrzeby całego magazynu.
- Dodano współbieżne testy JUnit registry: rejestracja kontra peer revoke, global wipe kontra wcześniej wydane ticket-y oraz izolacja innych peerów.

## Walidacja
- Lokalny runner shim-JUnit: 33/33 testy PASS na uruchomienie, 5 kolejnych uruchomień (165 wywołań testów); w teście wyścigu wykonano 500 iteracji łącznie.
- `kotlinc -Werror` dla registry, pin-set i wybranych testów: PASS.
- `CompanionProvisioningStore.kt` seam compile z atrapami Android/JSON/core API: PASS; nie zastępuje kompilacji Android SDK ani testów Android Keystore.
- Pełny Android `CompanionProvisioningStore` compile, Gradle/JUnit/Robolectric/Keystore, APK i testy urządzeniowe: NOT RUN.
- Transport i listener nie są uruchamiane.

## Ograniczenie
Jeśli Android Keystore odmówi usunięcia aliasu po błędzie commit, blokada jest gwarantowana w bieżącym procesie, ale nie można zagwarantować jej przetrwania restartu. Taki przypadek jest raportowany jako błąd z dodatkowym suppressed exception i wymaga ręcznej diagnostyki/recovery. Pełną weryfikację ścieżki Keystore trzeba wykonać w Android Studio.
