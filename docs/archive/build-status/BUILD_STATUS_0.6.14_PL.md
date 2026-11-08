# Status budowania i weryfikacji — AI MASTER PRO 0.6.14

## Zmiany
- Indeks metadanych projektu `ProjectCodeIndex` oraz selekcja plików na podstawie dopasowania ścieżek.
- Kontrolowany odczyt tekstu w `ProjectWorkspaceStore.readTextFile` z walidacją manifestu, ścieżki kanonicznej, rozmiaru, SHA-256, UTF-8 i wzorców sekretów.
- Blokada importu zastępczego, jeśli istniejący workspace ma różnice względem manifestu.
- Kontrola integralności uwzględnia dowiązania symboliczne.
- UI Centrum: podsumowanie indeksu, lista modułów/języków, podgląd wybranego pliku do 24 KB.

## Weryfikacje zakończone sukcesem
- Kompilacja Kotlin nowych modułów `ProjectWorkspaceStore.kt` i `ProjectCodeIndex.kt` przez `kotlinc` — PASS.
- Kompilacja nowych źródeł testowych — PASS.
- Wykonano 13 metod testowych (`ProjectWorkspaceStoreTest` + `ProjectCodeIndexTest`) w lokalnym runnerze z asercjami zgodnymi z używanymi metodami JUnit — 13/13 PASS. Nie jest to uruchomienie silnika JUnit/Robolectric.
- Dodatkowy smoke test na rzeczywistym filesystemie/JVM — PASS: import, manifest SHA-256, odczyt źródła, blokada traversal, blokada binariów, filtr sekretów, indeks, ranking ścieżek, wykrywanie manipulacji i blokada zastąpienia naruszonego workspace.
- Kontrola archiwum źródłowego ZIP — PASS.

## Niezweryfikowane / ograniczenia środowiska
- `./gradlew testDebugUnitTest --offline --no-daemon` nie uruchomił się: Gradle Wrapper próbował pobrać Gradle 8.13, ale środowisko nie rozwiązało `services.gradle.org` (`UnknownHostException`).
- Pełny build Android / APK oraz wykonanie testów JUnit/Robolectric — NIE WYKONANO.
- Podgląd plików jest ręcznie inicjowany z Centrum; nie przekazuje kodu automatycznie do modeli.
- Indeks bazuje na ścieżkach i rozszerzeniach; nie analizuje AST, symboli ani grafu zależności.
