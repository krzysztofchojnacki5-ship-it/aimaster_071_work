# Changelog 0.6.33 — współbieżność i własność klucza odbiornika Companion

## Zmiany
- Dodano test współbieżnego replay: dwie równoległe próby weryfikacji tej samej ramki w jednym odbiorniku dają dokładnie jedno `ACCEPTED`; druga próba otrzymuje `SEQUENCE_MISMATCH`.
- Dodano test niezależnej kopii klucza: modyfikacja/wyzerowanie tablicy klucza przekazanej do konstruktora nie zmienia klucza prywatnie przechowywanego przez odbiornik.
- Doprecyzowano dokumentację kodu, że `Receiver` jest synchronizowany. Mechanizm synchronizacji istniał już wcześniej; wersja dodaje testy regresyjne jego kontraktu.

## Weryfikacja
- Kotlin/JVM `-Werror`: PASS.
- Ukierunkowany zestaw Companion: 33/33 PASS (7 pairing + 12 frame protocol + 5 wire codec + 9 timeout policy).
- Pełny Gradle/Robolectric: NOT RUN.
- APK / urządzenie: NOT RUN.
- ZIP integrity: do potwierdzenia po spakowaniu.

## Granice
- Nie dodano TLS/mTLS, socketów, transportu Companion, desktop executora ani OS-level sandboxa.
- `PROJECT_TESTS` pozostaje NOT_RUN.

## Wersja
- `versionCode = 59`
- `versionName = 0.6.33`
