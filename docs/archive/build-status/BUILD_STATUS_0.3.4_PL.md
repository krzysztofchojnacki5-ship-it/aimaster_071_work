# Status budowania 0.3.4

- Wersja źródeł: 0.3.4 (versionCode 15).
- ZIP projektu: przygotowany; kontrola `unzip -t` wykonywana po spakowaniu.
- Dodano testy JUnit/Robolectric dla rezerwacji budżetu, rankingu kondycji modeli i agregacji metryk agentów. Nie zostały uruchomione.
- Wykonano niezależną kontrolę składni/kompilacji Kotlin CLI dla zmienionych klas core i TeamRunEngine z tymczasowymi stubami Android/JSON/ProviderClient. Obie grupy klas przeszły tę kontrolę. Nie jest to kompilacja aplikacji Android ani test integracyjny.
- Próba `./gradlew testDebugUnitTest --no-daemon` nie doszła do uruchomienia Gradle: wrapper nie mógł rozwiązać `services.gradle.org` (`UnknownHostException`).
- `assembleDebug` nie zostało wykonane, APK nie został zbudowany.

Wymagana końcowa weryfikacja w Android Studio: `Build > Make Project`, następnie `testDebugUnitTest` i test ręczny na urządzeniu. Nie należy traktować ZIP-a jako skompilowanego APK.
