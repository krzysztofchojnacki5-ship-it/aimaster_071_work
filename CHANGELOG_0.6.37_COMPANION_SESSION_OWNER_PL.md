# AI MASTER PRO 0.6.37 — właściciel cyklu życia sesji Companion

## Zakres zmiany

- Dodano `CompanionSessionOwner` jako pojedynczy, synchronizowany punkt zarządzania cyklem życia sesji.
- Jawne stany: `NEW`, `PAIRED`, `ACTIVE`, `TRANSFERRING`, `CLOSED`, `FAILED`.
- Właściciel przyjmuje wyłącznie receipt, który może skonsumować przez skonfigurowaną instancję `CompanionPairingAuthority`; obcy, podrobiony i ponownie użyty receipt jest odrzucany.
- `VerifiedCompanionPairing` zawiera nonce challenge, dzięki czemu właściciel może wyprowadzić klucz kierunku `COMPANION_TO_ANDROID` bez ponownego przekazywania sekretu do zewnętrznego kodu.
- Klucz pochodny jest przekazywany do odbiornika, a tymczasowa tablica klucza jest zerowana. PSK właściciela jest zerowany po próbie konwersji receipt.
- Właściciel sam tworzy i kontroluje politykę timeoutów; produkcyjny konstruktor używa `System.nanoTime`, a deterministyczny zegar jest dostępny wyłącznie wewnętrznie dla testów.
- Timeout powoduje terminalne `FAILED` i zamknięcie odbiornika. Uwierzytelniona ramka `CLOSE` oraz jawne `close()` powodują terminalne `CLOSED`.
- Tylko poprawnie uwierzytelnione ramki odświeżają aktywność. Błędna ramka nie przesuwa idle deadline.
- Transfer ma pojedynczy, ograniczony timeout. `finishTransferAfterValidation()` wolno wywołać dopiero po niezależnej walidacji kompletności i digestu transferu.

## Ważna granica zaufania

`ACTIVE` oznacza, że PSK challenge-response zostało zweryfikowane i lokalny odbiornik ramek jest gotowy. Nie oznacza to weryfikacji certyfikatu TLS/mTLS ani bezpiecznego połączenia sieciowego. Przed dostarczeniem ramek z przyszłego transportu wymagane jest osobne potwierdzenie tożsamości peer przez platformowy stos TLS. Kod nie otwiera socketów i nie uruchamia kodu z importowanych projektów.

## Testy

- Testy obejmują receipt obcy/podrobiony/jednorazowy, przejścia stanów, aktywację, odbiór ramki, brak odświeżania idle po błędnym MAC, CLOSE, transfer, timeout handshake, transfer timeout, hard lifetime i jawne zamknięcie.
- Wybrane moduły Kotlin/JVM z `-Werror`: 51/51 PASS.
- Cały zestaw powtórzony 10 razy: 510/510 PASS.
- Parsowanie Android XML: PASS.
- ZIP integrity: PASS.
- Pełny Gradle/Robolectric: NOT RUN.
- APK / urządzenie: NOT RUN.
- TLS/mTLS, provisionowanie, sockety, Companion desktop: NOT IMPLEMENTED.
- OS-level sandbox / `PROJECT_TESTS`: NOT IMPLEMENTED / NOT RUN.
