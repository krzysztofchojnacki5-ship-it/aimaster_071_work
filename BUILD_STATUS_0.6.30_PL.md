# AI MASTER PRO 0.6.30 — status weryfikacji

## Zakres

- Terminalny stan odbiornika ramek po poprawnie uwierzytelnionym `CLOSE`.
- Odrzucanie wszystkich ramek po zamknięciu jako `SESSION_CLOSED`.
- Jawne unieważnienie odbiornika `close()` i best-effort zerowanie prywatnej kopii klucza.
- Terminalne zamknięcie po uwierzytelnionym wyczerpaniu numeracji `Long.MAX_VALUE`.
- Ochrona przed nieautoryzowanym zamknięciem przez ramkę `CLOSE` z błędnym MAC.

## Wyniki

| Kontrola | Wynik | Uwagi |
|---|---|---|
| Kotlin/JVM compile wybranych modułów i testów z `-Werror` | PASS | Kompilacja bez ostrzeżeń |
| Zestaw regresyjny: pairing, frame, wire codec, executor, endpoint policy, cleartext | PASS | 33/33 testy, lekki runner JUnit; nie Gradle |
| Nowe testy odbiornika | PASS | 3 nowe przypadki: terminalny CLOSE, explicit close + sequence exhaustion, błędny MAC CLOSE |
| Pełny Gradle / Robolectric | NOT RUN | Nie uruchomiono pełnego buildu |
| APK / urządzenie | NOT RUN | APK nie zostało zbudowane ani zweryfikowane |
| TLS/mTLS / sockety / Companion desktop | NOT IMPLEMENTED | Brak transportu |
| Pełna maszyna stanów sesji | NOT IMPLEMENTED | Kontrakt projektowy; wymaga wiarygodnego adaptera TLS i niepodrabialnego dowodu peer identity |
| OS-level sandbox / `PROJECT_TESTS` | NOT IMPLEMENTED / NOT RUN | Brak rzeczywistej izolacji procesu |

## Ważne

`CLOSE` jest terminalny tylko po poprawnej weryfikacji MAC. Niepoprawna ramka nie może zamknąć odbiornika. Zamykanie zeruje kopię klucza przechowywaną przez odbiornik best-effort; nie gwarantuje wymazania wszystkich kopii w pamięci JVM. Ta zmiana nie zapewnia poufności transportu — HMAC nadal nie zastępuje TLS.
