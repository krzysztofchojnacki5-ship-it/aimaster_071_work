# AI MASTER PRO 0.6.29 — status weryfikacji

## Zakres

- Naprawa błędu DoS w `CompanionPairingAuthority.verify()`.
- Stałoczasowe porównanie challenge authenticator.
- Dwie regresje testowe dla prób zużycia challenge przez niepoprawne proof.
- Rozszerzenie dokumentu o kontrakt lifecycle sesji i transferu Companion.

## Wyniki

| Kontrola | Wynik | Uwagi |
|---|---|---|
| Kotlin/JVM compile pairing + testy z `-Werror` | PASS | Kompilacja bez ostrzeżeń |
| `CompanionPairingProtocolTest` | PASS | 7/7 testów; w tym 2 nowe regresje |
| Zestaw regresyjny Kotlin/JVM (`pairing`, ramki, kodek, executor, endpoint policy, cleartext) | PASS | 30/30 testów; kompilacja źródeł i testów z `-Werror`, lekki runner JUnit, nie Gradle |
| Pełny Gradle / Robolectric | NOT RUN | Nie uruchomiono pełnego buildu |
| APK / urządzenie | NOT RUN | APK nie zostało zbudowane ani zweryfikowane |
| TLS/mTLS / sockety / Companion desktop | NOT IMPLEMENTED | Dokumentacja i kontrakt, bez transportu |
| OS-level sandbox / `PROJECT_TESTS` | NOT IMPLEMENTED / NOT RUN | Brak rzeczywistej izolacji procesu |
| `BoundedResponseReader` | PASS | 4/4 testy harness; źródło kompilowane osobno z `-Werror` |
| XML manifestu / network security config | PASS | Parsowanie XML |
| ZIP integrity | PASS | `unzip -t` bez błędów |

## Ważne

Nieudany proof nie może już unieważnić prawidłowego challenge. Challenge zostaje zużyty dopiero po pełnym poprawnym uwierzytelnieniu; wygaśnięcie nadal usuwa oczekujące wpisy. Ta poprawka nie zapewnia poufności transportu — HMAC nadal nie zastępuje TLS.
