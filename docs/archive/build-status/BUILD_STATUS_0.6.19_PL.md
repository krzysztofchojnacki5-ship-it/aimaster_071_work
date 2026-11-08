# Status budowania i weryfikacji — AI MASTER PRO 0.6.19

## Wersja
- `versionName = 0.6.19`
- `versionCode = 44`
- Baza: 0.6.18.1 — bezpieczny prywatny workspace, transakcyjny zapis patcha i crash recovery.

## Zmiany
- Dodano `ProjectWorkspaceStore.verifySnapshot()` — kontrola manifestu, SHA-256, brakujących/zmienionych/nadmiarowych plików oraz symlinków.
- Dodano `restoreSnapshot()` — weryfikacja źródła, staging, ponowna kontrola stagingu i transakcyjne przywrócenie. Zdrowy bieżący workspace jest zachowywany w dodatkowym snapshocie bezpieczeństwa.
- Dodano `ProjectTestRunner` — sprawdza integralność bieżącego workspace, integralność snapshotu i zgodność rzeczywiście zmienionych ścieżek z ChangeSet.
- Centrum dowodzenia udostępnia akcję weryfikacji patcha; wykryty FAIL uruchamia rollback. Rejestr otrzymuje `ROLLED_BACK` wyłącznie po udanym przywróceniu.
- `ChangeSetStore` zapisuje poziom weryfikacji; `APPLIED` wymaga `PASS` z poziomu `PROJECT_TESTS`. Samo sprawdzenie integralności nie wystarcza.

## Wyniki testów wykonanych lokalnie
- Kompilacja Kotlin/JVM czystych komponentów i testów: PASS.
- Klasy: `ProjectPatchEngineTest`, `ProjectWorkspaceStoreTest`, `ProjectWorkspaceStoreApplyTest`, `ProjectTestRunnerTest`.
- `ChangeSetStore.kt` — kompilacja składniowa Kotlin/JVM z lekkimi stubami Android/JSON: PASS; testy Robolectric tego magazynu nie zostały uruchomione.
- **38/38 PASS, 0 FAIL** w lokalnym runnerze z lekkim stubem JUnit 4. To nie jest pełna Gradle/JUnit suite ani test Android UI.
- Testy obejmują także przywracanie snapshotu, odrzucenie uszkodzonego snapshotu, naprawę zmienionego workspace oraz status `NOT_RUN` bez fałszywego PASS.

## Ograniczenia / NOT RUN
- Pełny Gradle/APK: NOT RUN. `./gradlew --version` próbował pobrać Gradle 8.13, ale DNS `services.gradle.org` zakończył się `UnknownHostException`.
- Android SDK: brak skonfigurowanego `ANDROID_HOME` / `ANDROID_SDK_ROOT` i brak lokalnego `android.jar`.
- Pełna kompilacja `MainActivity` i testów Android/Robolectric: NOT RUN.
- Kompilacja/testy importowanego projektu: NOT RUN. Nie uruchamiamy skryptów Gradle/shell ani binariów bez rzeczywistego sandboxa procesu z egzekwowanymi limitami CPU, RAM, czasu i sieci.
- Kontrole workspace/snapshotu mogą mieć status PASS, ale status całej weryfikacji pozostaje `NOT_RUN`, dopóki kod projektu nie przejdzie testów w izolowanym executorze.
