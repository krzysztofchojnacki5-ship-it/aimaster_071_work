# AI MASTER PRO 0.6.23 — kontrakt parowania z companionem

## Zmiany

- Dodano `CompanionPairingAuthority`: lokalny kontrakt challenge-response dla przyszłego desktop companion.
- Wyzwanie zawiera wersję protokołu, losowy identyfikator sesji, losowy nonce 256-bit, identyfikator klienta i termin ważności; Android uwierzytelnia je HMAC-SHA256.
- Android uwierzytelnia challenge przez HMAC-SHA256. Companion weryfikuje ten HMAC, a następnie tworzy odpowiedź HMAC-SHA256 obejmującą wszystkie pola wyzwania, identyfikator companion i HMAC challenge.
- Companion weryfikuje HMAC wyzwania, a Android weryfikuje odpowiedź HMAC w stałym czasie. Dowód jest związany z oczekującą sesją; sesja jest usuwana także po nieudanej próbie, więc challenge nie może zostać użyty ponownie.
- Domyślna ważność wyzwania: 60 sekund; maksymalnie 5 minut. Maksymalnie 32 oczekujące sesje.
- Sekret parowania musi mieć co najmniej 128 bitów (16–64 bajty) i musi zostać dostarczony poza kanałem transportowym. Implementacja nie zapisuje go, nie loguje i nie przesyła.
- Audyt 0.6.22: skaner teraz usuwa bajty NUL przed dopasowaniem wzorców, dzięki czemu wykrywa typowe sekrety ASCII zapisane jako UTF-16LE/BE. Odczyt jest strumieniowy i limitowany także w trakcie odczytu, co zabezpiecza przed wyścigiem zmiany rozmiaru pliku.
- Eksport przerywa skanowanie natychmiast po przekroczeniu limitu 40 MB, zamiast skanować resztę snapshotu.
- Walidacja gotowego ZIP czyta manifest strumieniowo z limitem 2 MB; nie wykonuje nieograniczonego `readBytes()` na wpisie archiwum.
- Podniesiono `versionCode` do 49 i `versionName` do 0.6.23.

## Testy / dowody

- Kotlin/JVM compile komponentów: `ExecutorProtocol`, `BundleContentScanner`, `CompanionPairingProtocol`, `ProjectWorkspaceStore` — PASS (lekki stub dla zależności `ProjectPatchEngine.Preview` i `ChangeSafetyGuard`).
- Wybrane testy `CompanionPairingProtocolTest`, `ExecutorBundleExportTest`, `ExecutorProtocolTest` — 17/17 PASS. Harness Kotlin/JVM używa lekkiego stubu JUnit i stubów tylko dla niepowiązanych zależności `ProjectPatchEngine.Preview` / `ChangeSafetyGuard`; stub `ChangeSafetyGuard` nie jest używany przez testowaną ścieżkę eksportu.
- Pełny Gradle / Android unit tests / APK — NOT RUN. W poprzednim etapie potwierdzono brak DNS do `services.gradle.org`; nie deklarujemy wyniku pełnego builda.

## Granice bezpieczeństwa

- To jest wyłącznie kontrakt kryptograficznego parowania. Nie dodano jeszcze socketów, discovery, TLS, transferu bundle, desktop companion ani OS-level sandboxa.
- HMAC-PSK zakłada bezpieczne, poza-pasmowe provisionowanie sekretu. Nie należy wysyłać sekretu w QR, logach ani przez niezaufany kanał bez dodatkowego projektu bezpiecznego provisionowania.
- Nie ma jeszcze ECDH ani automatycznej rotacji kluczy. Nie należy traktować samego parowania jako szyfrowania transportu.
- `PROJECT_TESTS` pozostaje `NOT_RUN`; kod importowanego projektu nadal nie może być uruchamiany.
