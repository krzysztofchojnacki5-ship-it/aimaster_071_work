# Changelog 0.6.32 — spójność próbkowania zegara timeoutów Companion

## Zmiana
- Usunięto podwójne odczyty zegara w przejściach `markHandshakeFinished`, `recordAuthenticatedActivity`, `beginTransfer` i `finishTransfer`.
- Każda operacja pobiera jedną próbkę zegara monotonicznego; ta sama próbka służy do kontroli deadline i zapisu odpowiedniego znacznika czasu.
- Dodano test regresyjny symulujący zmianę czasu między kolejnymi odczytami. Test wykrywał możliwość ukrycia granicy idle timeout przez drugi odczyt po sprawdzeniu deadline.
- Zachowano inkluzywne granice `elapsed >= limit`, priorytet hard lifetime i obsługę zawinięcia `nanoTime`.

## Weryfikacja
- Kotlin/JVM `-Werror`: PASS.
- Ukierunkowany zestaw Companion: 31/31 PASS (7 pairing + 10 frame protocol + 5 wire codec + 9 timeout policy).
- Pełny Gradle/Robolectric: NOT RUN.
- APK / urządzenie: NOT RUN.
- ZIP integrity: do potwierdzenia po spakowaniu.

## Granice
- Polityka timeoutów nadal nie uwierzytelnia peerów i nie jest zintegrowana z transportem.
- TLS/mTLS, sockety, desktop Companion i OS-level sandbox pozostają NOT IMPLEMENTED.
- `PROJECT_TESTS` pozostaje NOT_RUN.

## Wersja
- `versionCode = 58`
- `versionName = 0.6.32`
