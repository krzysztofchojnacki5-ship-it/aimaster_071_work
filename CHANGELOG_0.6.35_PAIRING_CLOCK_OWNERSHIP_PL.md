# Changelog 0.6.35 — własność zegara wygasania parowania

## Znalezisko audytu
`CompanionPairingAuthority.begin()`, `verify()` i `pendingCount()` przyjmowały opcjonalny parametr `now`. Ułatwiało to testy, ale pozostawiało publicznemu wywołującemu możliwość podania dowolnej wartości czasu przy każdej operacji. Przypadkowe użycie zegara ściennego, cofnięcie czasu lub niespójne źródła czasu mogły wydłużyć albo skrócić TTL challenge.

## Zmiana
- Produkcyjne operacje parowania pobierają czas wyłącznie z zegara monotonicznego przypisanego do instancji authority.
- Usunięto parametr `now` z publicznych metod `begin()`, `verify()` i `pendingCount()`.
- Wstrzykiwanie zegara pozostaje wewnętrznym seamem modułu do deterministycznych testów; zwykły konstruktor produkcyjny sam wybiera zegar monotoniczny.
- Weryfikacja i czyszczenie wygasłych challenge korzystają z czasu odczytanego wewnątrz tej samej operacji.
- Testy pokrywają granicę TTL, wygaśnięcie, usuwanie przeterminowanych wpisów przed sprawdzeniem limitu pojemności i standardowy konstruktor.

## Weryfikacja
- Kotlin/JVM wybranych modułów i testów z `-Werror`: PASS.
- Ukierunkowany zestaw Companion: 36/36 PASS (9 pairing + 13 frame protocol + 5 wire codec + 9 timeout policy), lekki runner, nie Gradle.
- Powtórzenia całego zestawu 10×: do potwierdzenia.
- XML manifestu i network security config: do potwierdzenia.
- Pełny Gradle/Robolectric: NOT RUN.
- APK / urządzenie: NOT RUN.
- ZIP integrity: do potwierdzenia po spakowaniu.

## Granice
Nie dodano TLS/mTLS, socketów, provisionowania ani rotacji kluczy, desktop executora, maszyny stanów sesji ani OS-level sandboxa. `PROJECT_TESTS` pozostaje `NOT RUN`.

## Wersja
- `versionCode = 61`
- `versionName = 0.6.35`
