# Status 0.3.8

- Baza: 0.3.7 (versionCode 18)
- Wersja: 0.3.8 (versionCode 19)
- Pełny projekt Android Studio; APK nie jest dołączony.
- Wykonano poprawki identyfikacji ocen, zachowania metadanych, ograniczenia kontekstu analizy i filtrowania nieudanych przebiegów w selektorze ocen.
- Dodano testy regresyjne.
- Integralność ZIP sprawdzona.
- `./gradlew testDebugUnitTest` / pełny build nieuruchomione z powodu braku lokalnego Gradle 8.13 i braku dostępu do `services.gradle.org`. Nie deklarować kompilacji jako zaliczonej.
