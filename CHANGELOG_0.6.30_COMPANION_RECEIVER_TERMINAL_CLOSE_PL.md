# Changelog 0.6.30 — terminalność odbiornika ramek Companion

## Zmiana bezpieczeństwa

- `CompanionFrameProtocol.Receiver` po poprawnie uwierzytelnionej ramce `CLOSE` przechodzi w stan terminalny.
- Wszystkie późniejsze ramki są odrzucane kodem `SESSION_CLOSED`.
- Dodano `close()` do jawnego unieważnienia odbiornika przez właściciela (np. timeout, błąd transportu lub zamykanie sesji).
- Zamknięcie zeruje best-effort kopię klucza sesyjnego przechowywaną przez odbiornik.
- Uwierzytelniona ramka z numerem `Long.MAX_VALUE` kończy odbiornik kodem `SEQUENCE_EXHAUSTED`.
- `CLOSE` z niepoprawnym MAC nie zamyka odbiornika; nieautoryzowany pakiet nie może wywołać terminalnego DoS.

## Testy

- Dodano regresje: uwierzytelniony CLOSE jest terminalny; jawne close jest terminalne i idempotentne; wyczerpanie sekwencji zamyka odbiornik; niepoprawny MAC CLOSE nie zamyka odbiornika.
- Zestaw bezpieczeństwa Kotlin/JVM: 33/33 PASS z `-Werror`.

## Granice implementacji

- Nie dodano pełnej maszyny stanów sesji, TLS/mTLS, socketów, certyfikatów, discovery, desktop Companion ani sandboxa.
- Nie istnieje jeszcze wiarygodny adapter TLS, który może wystawić dowód tożsamości peer; dlatego stan `AUTHENTICATED` pozostaje nieosiągalny przez nowy publiczny API lifecycle.
- `PROJECT_TESTS` pozostaje `NOT_RUN`.

## Wersja

- `versionCode = 56`
- `versionName = 0.6.30`
