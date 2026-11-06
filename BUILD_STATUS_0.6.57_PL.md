# BUILD STATUS — 0.6.57-SOURCE

**Typ:** poprawka współbieżności Companion TLS + test regresyjny; nie wydanie APK.  
**Baza:** 0.6.56-SOURCE.  
**Aplikacja:** `versionName=0.6.54`, `versionCode=80` — bez zmian.  
**APK:** nie utworzono.

## Zmiany
- `CompanionPinnedTlsProfile.accept()` serializuje `soTimeout` + `accept()` na monitorze danego listenera.
- Handshake pozostaje poza monitorem listenera.
- `closeServer()` odłącza listener od rejestru profilu i zamyka go poza monitorem profilu; nie czeka na blokadę accept.
- `CompanionTlsContextTest.concurrentAcceptCallsDoNotOverwriteSharedListenerTimeout` dodaje regresyjny test loopback.

## Wyniki
- Kompilacja trzech klas TLS przez `kotlinc -Werror`: PASS.
- Dedykowany harness Kotlin/JVM z rzeczywistym loopback mTLS i certyfikatami testowymi: PASS.
- Nowy test JUnit: dodany, NOT RUN.
- Gradle unit tests / Robolectric: NOT RUN.
- `assembleDebug`: NOT RUN.
- Android Keystore / urządzenie: NOT RUN.

## Ograniczenia i status
- R5: poprawione na poziomie źródła; loopback harness potwierdził izolację timeoutów. Pełny test JUnit czeka na Gradle.
- Pkt 4: OPEN. R1 ticket przed handshake, R2 terminalny owner, R3 callback poza lockiem, R4 częściowy `wipeAllTrust`, R6 anulowanie receiptów nadal wymagają rozwiązania.
- Pkt 5: PARTIAL.
- Pkt 6: BLOCKED; nie uruchamiać transportu.
