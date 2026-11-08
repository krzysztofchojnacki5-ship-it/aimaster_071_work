# Status weryfikacji 0.6.0

- Testy logiki polityki adaptacyjnej i samokontroli dodane do `app/src/test`.
- Testy fingerprintu sprawdzają stabilność klucza i unieważnianie po zmianie kontekstu/załącznika/modelu.
- Weryfikacja pełnego Gradle/Android build wymaga środowiska z Gradle 8.13 i Android SDK. W środowisku przygotowania poprzedniej wersji wrapper nie mógł pobrać Gradle z powodu braku DNS; nie należy traktować tego ZIP-a jako potwierdzonego APK.

## Wyniki dostępnych testów lokalnych

- `AdaptiveExecutionPolicy` i `MasterResponseCheck`: odizolowany harness Kotlin zakończony wynikiem `PASS` (proste/złożone zadania, jawny zespół, kontrola instrukcji i kompletnej odpowiedzi).
- `TaskResultCache` fingerprint: dodano testy JUnit stabilności i unieważniania klucza; pełne uruchomienie JUnit wymaga Gradle/Android SDK i nie zostało wykonane.
- Próba `./gradlew --offline testDebugUnitTest` zatrzymała się przed startem Gradle: wrapper próbował pobrać Gradle 8.13, ale środowisko nie miało DNS (`UnknownHostException: services.gradle.org`).
- APK nie został zbudowany. ZIP zawiera projekt Android Studio, a nie gotowy instalator.
