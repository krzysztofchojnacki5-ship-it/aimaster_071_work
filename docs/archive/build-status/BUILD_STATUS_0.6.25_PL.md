# Build status — AI MASTER PRO 0.6.25

- Wersja: `0.6.25` (`versionCode=51`)
- Zakres: ograniczony binarny kodek ramek companion `AIMF`.

## Wyniki

| Kontrola | Wynik | Zakres |
|---|---|---|
| Kotlin/JVM compile (`-Werror`) | PASS | `CompanionPairingProtocol`, `CompanionFrameProtocol`, `CompanionFrameWireCodec` |
| Wybrane testy Kotlin/JVM | PASS 17/17 | 5 testów parowania, 7 testów ramek/KDF, 5 testów kodeka; lekki JUnit API harness |
| Maksymalny payload 1 MiB | PASS | serializacja i dekodowanie w limicie 1 400 000 B |
| Odrzucanie wejścia oversized/truncated/trailing | PASS | testy kodeka |
| Kanoniczny Base64URL | PASS | niekanoniczny alias odrzucany |
| Weryfikacja HMAC po dekodowaniu | PASS | Receiver odrzuca zmieniony MAC |
| Pełny Gradle test suite | NOT RUN | nie deklarujemy pełnego buildu Android/Gradle |
| Android APK | NOT RUN | brak pełnego Android builda |
| UI / urządzenie Android | NOT RUN | brak testu na urządzeniu |
| TLS / poufność transportu / provisionowanie PSK | NOT IMPLEMENTED | nadal wymagane przed przesyłaniem danych poufnych |
| Desktop companion / OS sandbox | NOT IMPLEMENTED | poza zakresem |
| `PROJECT_TESTS` | NOT RUN | kod importowanego projektu nie jest uruchamiany |

## Ważne ograniczenie

Kodek jest parserem/serializatorem, nie kanałem bezpiecznym. HMAC ramek nie szyfruje treści. Nie wysyłać sekretów ani poufnych danych przez niezabezpieczony transport.
