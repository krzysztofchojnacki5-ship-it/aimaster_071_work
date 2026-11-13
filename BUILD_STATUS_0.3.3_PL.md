# Status weryfikacji 0.3.3

- ZIP utworzony i sprawdzony przez `unzip -t`.
- Zmieniono wersję aplikacji na 0.3.3 (versionCode 14).
- Dodano testy jednostkowe dla ApiTokenTracker i poprawnego oznaczania REAL/ESTIMATED.
- Próba `./gradlew testDebugUnitTest --no-daemon` nie rozpoczęła testów: Gradle Wrapper nie mógł rozwiązać `services.gradle.org` (`UnknownHostException`).
- Nie wykonano kompilacji Android ani budowy APK. Nie deklarować BUILD SUCCESSFUL.
- Testy dodano, ale nie zostały uruchomione w tym środowisku.
