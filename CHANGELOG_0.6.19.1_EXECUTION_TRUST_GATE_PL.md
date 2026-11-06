# AI MASTER PRO 0.6.19.1 — utwardzenie bramki zaufania testów

## Dlaczego powstała poprawka

Audyt 0.6.19 ujawnił niespójność: `ChangeSetStore.recordVerification()` przyjmował od dowolnego wywołującego tekstowy wynik `PASS` z etykietą `PROJECT_TESTS`, mimo że aplikacja nie miała jeszcze rzeczywistego izolowanego executora. W połączeniu z przejściem statusu mogło to pozwolić na fałszywe oznaczenie zmiany jako `APPLIED`.

## Naprawy

- Usunięto publiczną ścieżkę zapisu dowolnego poziomu weryfikacji.
- `recordWorkspaceVerification()` zapisuje wyłącznie wyniki `WORKSPACE_INTEGRITY`.
- `recordTests()` pozostaje ścieżką zgodności wstecznej i zawsze zapisuje `LEGACY_UNVERIFIED`; jej PASS nie może odblokować `APPLIED`.
- Po statusie końcowym `APPLIED` nie można już podmienić wyniku weryfikacji ani przypisać nowego snapshotu.
- Przy odczycie danych zapisanych przez 0.6.19 etykieta `PROJECT_TESTS` jest degradowana do `LEGACY_UNVERIFIED` + `NOT_RUN`, ponieważ w tej wersji nie istniał rzeczywisty executor. Stare deklaracje nie mogą stać się dowodem po aktualizacji.
- `ProjectTestRunner` odrzuca nieprawidłowe i powielone ścieżki z ChangeSet oraz zamienia wyjątek podczas porównania zakresu na jawny `FAIL`, zamiast przerywać weryfikację bez raportu.
- Akcja Centrum dowodzenia korzysta z węższego API `recordWorkspaceVerification()`.

## Testy i ograniczenia

- Testy `ProjectPatchEngineTest`, `ProjectWorkspaceStoreTest`, `ProjectWorkspaceStoreApplyTest` i `ProjectTestRunnerTest`: **39/39 PASS** w lokalnym runnerze JVM ze stubem JUnit 4.
- `ChangeSetStore.kt` + `ChangeSafetyGuard.kt`: kompilacja składniowa Kotlin/JVM ze stubami Android/JSON: PASS.
- Testy Robolectric `ChangeSetStoreTest`, pełny Gradle/APK, pełna kompilacja `MainActivity` i testy UI: NOT RUN — brak Android SDK i dystrybucji Gradle 8.13 w środowisku.
- Nie ma jeszcze rzeczywistego sandboxa procesu. `PROJECT_TESTS` pozostaje niedostępne; kod importowanego projektu nie jest uruchamiany.

## Wersja

- `versionCode = 45`
- `versionName = 0.6.19.1`
