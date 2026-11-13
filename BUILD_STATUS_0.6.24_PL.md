# Build status — AI MASTER PRO 0.6.24

- Wersja: `0.6.24` (`versionCode=50`)
- Zakres: klucze sesji z separacją domeny i kierunku, uwierzytelnione ramki companion, integralność payloadu, kontrola kolejności i limity.

## Wyniki

| Kontrola | Wynik | Zakres |
|---|---|---|
| Kotlin/JVM compile protokołów | PASS | `CompanionPairingProtocol` + `CompanionFrameProtocol` |
| Wybrane testy Kotlin/JVM | PASS 12/12 | 5 testów parowania + 7 testów ramek i KDF; lekki stub JUnit |
| Testy regresyjne 0.6.23 | PASS 17/17 | istniejący harness: parowanie, eksport bundle, manifest i receipt; bez zmian w tym zakresie |
| Replay / kolejność | PASS | odrzucenie powtórzonej i nieoczekiwanej sekwencji |
| Direction binding | PASS | odrzucenie ramki odbitej w przeciwnym kierunku; osobne klucze per kierunek |
| Integralność / MAC | PASS | zmieniony digest lub MAC odrzucany; błędna ramka nie zużywa sekwencji |
| Limity | PASS | klucz, sesja, numer i payload >1 MiB odrzucane; długość Base64 sprawdzana przed dekodowaniem |
| Pełny Gradle test suite | NOT RUN | dystrybucja Gradle 8.13 niedostępna w poprzedniej próbie z powodu DNS; nie deklarujemy pełnego buildu |
| Android APK | NOT RUN | brak pełnego Android builda |
| UI / urządzenie Android | NOT RUN | brak testu na urządzeniu |
| Transport sieciowy / TLS | NOT IMPLEMENTED | protokół ramki nie otwiera połączeń |
| Desktop companion / OS sandbox | NOT IMPLEMENTED | poza zakresem 0.6.24 |
| `PROJECT_TESTS` | NOT RUN | nie uruchamiamy kodu importowanego projektu |

## Ważne ograniczenie

Ramki są uwierzytelnione i integralne, ale niezaszyfrowane. HMAC nie zapewnia poufności. Nie używać tego kontraktu samodzielnie do transferu wrażliwych danych przez sieć. Przed transportem wymagane są bezpieczne provisionowanie klucza i zweryfikowana warstwa szyfrująca (np. TLS z walidacją tożsamości).
