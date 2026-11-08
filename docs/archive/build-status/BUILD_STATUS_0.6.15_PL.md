# Status budowania i weryfikacji — AI MASTER PRO 0.6.15

## Wersja
- `versionName = 0.6.15`
- `versionCode = 38`

## Zmiany
- Dodano `ProjectStructureAnalyzer.kt` i testy `ProjectStructureAnalyzerTest.kt`.
- Dodano ręczną analizę symboli Kotlin/Java i graf zależności w Centrum dowodzenia. Analiza oraz kontrola SHA-256 wykonują się w tle, a UI pokazuje stan pracy.
- Rozszerzono filtr sekretów w `ProjectWorkspaceStore.readTextFile` o kolejne typowe nazwy sekretów.
- Aktualizowano changelog i roadmapę.

## Weryfikacje wykonane
- Kompilacja Kotlin `ProjectWorkspaceStore.kt`, `ProjectCodeIndex.kt` i `ProjectStructureAnalyzer.kt` przez lokalny `kotlinc` — PASS.
- Kompilacja i uruchomienie 22 metod testowych dla workspace, indeksu metadanych i analizy strukturalnej — 22/22 PASS.
- Testy obejmują m.in. komentarze/literały, deklaracje Kotlin/Java, enumy i interfejsy adnotacji, importy i krawędzie grafu, zależności modułów, duplikaty typów, limity, odrzucanie plików oraz nowe warianty filtrów sekretów.
- Smoke test na rzeczywistym kodzie projektu: 37 plików Kotlin/Java, 286 rozpoznanych symboli, 116 importów, 7 zależności plikowych, 104 importy bez dopasowania w projekcie (w większości zewnętrzne biblioteki), 2 pliki pominięte przez limity/filtr — bez awarii analizatora.
- Dodatkowy test limitu łącznego odczytu potwierdza twardy próg 4 MB; numery linii wyznaczane są przez indeks pozycji nowych linii, bez wielokrotnego skanowania prefiksów pliku.

## Ograniczenia weryfikacji
- Testy wykonano w lokalnym runnerze z małym stubem adnotacji/asercji JUnit, nie przez prawdziwy silnik JUnit/Robolectric.
- Pełny Gradle build, pełny `testDebugUnitTest` i APK — NIE WYKONANO.
- Próba parsowania `MainActivity.kt` przez samodzielny `kotlinc` bez Android SDK/classpath zakończyła się oczekiwanymi błędami nierozpoznanych referencji Android; nie znaleziono diagnostyk `expecting`/`unexpected tokens`. Nie jest to kompilacja UI.
- W poprzednim etapie Gradle Wrapper nie mógł pobrać Gradle 8.13 z powodu błędu DNS (`services.gradle.org`). Nie deklarujemy kompilacji Android UI jako potwierdzonej.
- Analizator jest ograniczonym skanerem strukturalnym, a nie pełnym AST.
