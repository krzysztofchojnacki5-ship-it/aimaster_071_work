# Status budowania 0.3.5

- Wersja źródeł: 0.3.5 (versionCode 16).
- Paczka jest pełnym projektem Android Studio, nie plikiem APK.
- Kontrola składni/kompilacji Kotlin CLI z tymczasowymi stubami przeszła dla trzech zmienionych obszarów: `TeamRunEngine` + `ProviderQuotaScheduler`, `ProviderClient`, `FreeModelsCatalog`.
- Smoke test klasyfikacji lokalnych/publicznych endpointów Ollama: PASS.
- Dodano testy regresyjne dla braku `usage` w Gemini OpenAI-compatible i rozpoznawania Ollama w prywatnej sieci LAN. Nie zostały uruchomione w JUnit/Robolectric.
- `./gradlew testDebugUnitTest --no-daemon` nie doszedł do uruchomienia Gradle: wrapper nie rozwiązał `services.gradle.org` (`UnknownHostException`).
- `assembleDebug` nie zostało wykonane; APK nie powstał.

Wymagana weryfikacja po stronie Android Studio: `Build > Make Project`, następnie `testDebugUnitTest` oraz test ręczny połączenia Gemini/OpenAI-compatible bez metadanych `usage`, testu profilu Ollama LAN i anulowania batcha.
