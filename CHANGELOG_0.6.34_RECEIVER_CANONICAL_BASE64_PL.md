# Changelog 0.6.34 — kanoniczny Base64URL w granicy odbiornika

## Znalezisko audytu
`CompanionFrameWireCodec.decode()` odrzucał niekanoniczny Base64URL, lecz `CompanionFrameProtocol.Receiver.verify()` był publiczną granicą zaufania i mógł zostać wywołany bezpośrednio z ręcznie utworzonym obiektem `Frame`. Dekoder Java toleruje niektóre alternatywne kodowania tych samych bajtów (np. `AA` i `AB` dla pojedynczego bajtu zerowego).

## Zmiana
- Odbiornik po dekodowaniu sprawdza, czy ponowne kodowanie bez paddingu daje dokładnie oryginalny tekst.
- Niekanoniczna reprezentacja zwraca `NON_CANONICAL_BASE64`.
- Odrzucenie nie zużywa numeru sekwencyjnego; poprawna ramka z oczekiwanym numerem może zostać następnie przyjęta.
- Kodek binarny zachowuje własną walidację — obie granice są niezależne.

## Weryfikacja
- Kotlin/JVM wybranych modułów i testów z `-Werror`: PASS.
- Ukierunkowany zestaw Companion: 34/34 PASS (7 pairing + 13 frame protocol + 5 wire codec + 9 timeout policy), lekki runner, nie Gradle.
- Pełny Gradle/Robolectric: NOT RUN.
- APK / urządzenie: NOT RUN.
- ZIP integrity: do potwierdzenia po spakowaniu.

## Granice
Nie dodano TLS/mTLS, socketów, provisionowania kluczy, desktop executora ani OS-level sandboxa. `PROJECT_TESTS` pozostaje `NOT RUN`.

## Wersja
- `versionCode = 60`
- `versionName = 0.6.34`
