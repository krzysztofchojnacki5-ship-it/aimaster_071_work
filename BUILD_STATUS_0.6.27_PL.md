# AI MASTER PRO 0.6.27 — status techniczny

## Zakres
- Usunięto nieograniczony `readText()` z odczytu odpowiedzi HTTP w `ProviderClient`.
- Wprowadzono strumieniowy odczyt UTF-8 ograniczony do 4 MiB.
- Zbyt duża odpowiedź: `RESPONSE_TOO_LARGE`; błędne UTF-8: `INVALID_RESPONSE_ENCODING`.
- Zachowano istniejące zachowanie anulowania, retry i walidacji endpointów.
- Wersja: `versionCode=53`, `versionName=0.6.27`.

## Weryfikacja
- Kotlin/JVM `BoundedResponseReader.kt` z `-Werror`: PASS.
- Harness graniczny: 4/4 PASS.
- Pełny Gradle: NOT RUN.
- Android APK / test urządzenia: NOT RUN.
- Integracyjne testy `ProviderClientNetworkTest`: NOT RUN.
