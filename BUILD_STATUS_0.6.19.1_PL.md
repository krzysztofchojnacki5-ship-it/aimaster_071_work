# Status budowania i weryfikacji — AI MASTER PRO 0.6.19.1

## Wersja
- `versionName = 0.6.19.1`
- `versionCode = 45`
- Baza: 0.6.19 — workspace verification + rollback.

## Główna poprawka
Audyt ujawnił lukę w bramce zaufania: API rejestru pozwalało wpisać dowolny tekst jako `PASS / PROJECT_TESTS`, mimo że rzeczywisty executor jeszcze nie istniał. W 0.6.19.1 ta ścieżka została zamknięta.

- Wyniki kontroli workspace zapisuje wyłącznie `recordWorkspaceVerification()`.
- Ręczne/stare raporty trafiają do `LEGACY_UNVERIFIED` i nie mogą promować zmiany do `APPLIED`.
- Historyczne wpisy z `PROJECT_TESTS` zapisane przez wersję bez executora są przy odczycie degradowane do `NOT_RUN / LEGACY_UNVERIFIED`.
- Po `APPLIED` nie można nadpisać dowodu ani snapshotu.
- Runner dodatkowo odrzuca niebezpieczne/powielone ścieżki i raportuje błąd porównania jako FAIL.

## Testy
- `ProjectPatchEngineTest`, `ProjectWorkspaceStoreTest`, `ProjectWorkspaceStoreApplyTest`, `ProjectTestRunnerTest`: **39/39 PASS** w lokalnym runnerze JVM ze stubem JUnit 4.
- `ChangeSetStore.kt` i `ChangeSafetyGuard.kt`: kompilacja składniowa Kotlin/JVM ze stubami Android/JSON: PASS.
- Testy Robolectric magazynu zmian: NOT RUN.
- Pełny Gradle/APK, pełna kompilacja Android UI/MainActivity: NOT RUN — brak Android SDK i dystrybucji Gradle 8.13.

## Granice
- Nie uruchamiamy skryptów Gradle/shell ani binariów z importowanego ZIP-a.
- `PROJECT_TESTS` pozostaje niedostępne do czasu wdrożenia prawdziwego executora z izolacją i egzekwowanymi limitami zasobów/sieci.
