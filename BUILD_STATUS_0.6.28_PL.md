# AI MASTER PRO 0.6.28 — status weryfikacji

## Zakres

- Walidacja endpointów dostawców przed otwarciem połączenia.
- Monotoniczny TTL challenge parowania Companion, odporny na zmianę zegara ściennego.
- Rozszerzenie testów polityki endpointów i hostów cleartext.
- Dokument architektury bezpieczeństwa przyszłego Companion TLS/mTLS.
- Aktualizacja wersji i roadmapy.

## Wyniki

| Kontrola | Wynik | Uwagi |
|---|---|---|
| Kotlin/JVM compile `ProviderEndpointPolicy` + `CleartextEndpointPolicy` z `-Werror` | PASS | Brak ostrzeżeń kompilatora |
| Harness polityki endpointów | PASS | 18/18 przypadków |
| Zakazane schematy `file:` / `ftp:` | PASS | Odrzucone przed otwarciem połączenia |
| Host/port/user-info | PASS | Błędne wartości odrzucone |
| HTTP lokalne / LAN / klucz API / query token | PASS | W tym percent-encoded nazwy parametrów i `secret` |
| Zestaw regresyjny Kotlin/JVM z `-Werror` | PASS | 29/29 testów: pairing, ramki, kodek, executor, cleartext i endpoint policy; uruchomione lekkim runnerem z minimalną warstwą JUnit, nie przez Gradle |
| `BoundedResponseReader` regresja | PASS | 4/4 przypadki |
| XML manifestu i network security config | PASS | Oba pliki poprawnie parsują się jako XML; nie zmieniano ich w 0.6.28 |
| Pełny Gradle / testy Robolectric | NOT RUN | Wrapper Gradle 8.13 nie jest lokalnie pobrany; nie ponawiano pobrania z powodu znanej niedostępności DNS `services.gradle.org` |
| APK / urządzenie | NOT RUN | Nie twierdzimy, że APK zostało zbudowane lub uruchomione |
| Companion TLS/mTLS / sockety / desktop executor | NOT IMPLEMENTED | Dostarczono projekt architektury, nie implementację |
| OS-level sandbox / `PROJECT_TESTS` | NOT IMPLEMENTED / NOT RUN | Kod importowanego projektu nadal nie jest uruchamiany |

## Ważne

Ta wersja utwardza walidację endpointów i dokumentuje docelowy transport. Nie zapewnia jeszcze poufnego transportu Companion. HMAC w istniejących ramkach nadal zapewnia integralność/autentykację, a nie szyfrowanie.
