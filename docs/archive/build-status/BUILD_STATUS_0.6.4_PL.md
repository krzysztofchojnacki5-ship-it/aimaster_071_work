# Status budowania — 0.6.4

- Źródła: zaktualizowane.
- Zakres: live agent communication log + peer review stage.
- Testy: dodano asercje dziennika REQUEST/REPORT w TeamRunEngineNetworkTest.
- Wynik kompilacji: NIEZWERYFIKOWANY.
- Powód: Gradle Wrapper 8.13 nie pobrał dystrybucji (`UnknownHostException: services.gradle.org`).
- APK: nie wygenerowano.

Przed instalacją uruchomić w Android Studio:
1. `./gradlew testDebugUnitTest`
2. `./gradlew assembleDebug`
