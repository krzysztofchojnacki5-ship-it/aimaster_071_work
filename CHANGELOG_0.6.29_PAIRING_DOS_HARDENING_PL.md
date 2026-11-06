# Changelog 0.6.29 — utwardzenie parowania Companion

## Naprawiony błąd bezpieczeństwa

- `CompanionPairingAuthority.verify()` wcześniej usuwał challenge z mapy pending przed weryfikacją MAC proof. Osoba znająca `sessionId` mogła wysłać niepoprawny proof i zużyć challenge, blokując prawidłowe parowanie (DoS).
- Challenge jest teraz pobierany bez usuwania. Weryfikacja sekretu/MAC i powiązania challenge odbywa się przed konsumpcją; challenge jest usuwany wyłącznie po pełnym, poprawnym uwierzytelnieniu.
- Błędny sekret i zmieniony `challengeAuthenticator` pozostawiają challenge dostępny do prawidłowej próby, do czasu wygaśnięcia.
- Porównanie `challengeAuthenticator` jest stałoczasowe przez `MessageDigest.isEqual`.

## Testy

- Dodano test: błędny sekret nie zużywa challenge, a prawidłowy proof nadal działa dokładnie raz.
- Dodano test: zmieniony authenticator nie zużywa challenge, a oryginalny proof nadal działa.
- Zachowano zachowanie EXPIRED i REPLAY_OR_UNKNOWN_SESSION.

## Materiały architektoniczne

- Rozszerzono `COMPANION_TRANSPORT_SECURITY_DESIGN_0.6.30_PL.md` (dokument pierwotnie utworzony w 0.6.29 i rozszerzony w 0.6.30) o formalny kontrakt maszyny stanów sesji i transferu: pairing, authenticated, transferring, transfer verified, closed/failed.
- Rozdzielono odebranie i zweryfikowanie bundle od zgody na wykonanie kodu. Sam transfer nigdy nie może ustawić `PROJECT_TESTS=PASS`.

## Wersja

- `versionCode = 55`
- `versionName = 0.6.29`

## Ograniczenia

- Nie dodano socketów, TLS/mTLS, certyfikatów, discovery, desktop executora ani sandboxa.
- `PROJECT_TESTS` pozostaje `NOT_RUN`; kod importowanego projektu nie jest wykonywany.
