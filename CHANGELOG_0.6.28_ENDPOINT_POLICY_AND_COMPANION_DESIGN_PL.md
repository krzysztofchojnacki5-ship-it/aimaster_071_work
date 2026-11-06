# Changelog 0.6.28 — walidacja endpointów i projekt Companion

## Zmiany w kodzie

- Dodano `ProviderEndpointPolicy` jako wspólną, fail-closed bramkę walidacji adresu przed otwarciem połączenia.
- Termin ważności challenge parowania Companion opiera się teraz na monotonicznym zegarze procesu, a nie na zegarze ściennym urządzenia; zmiana daty/godziny systemowej nie wydłuża ani nie skraca TTL.
- `ProviderClient` odrzuca teraz schematy inne niż HTTP/HTTPS, adresy bez hosta, błędne porty i URL zawierające user-info. Eliminuje to m.in. niekontrolowaną ścieżkę `ClassCastException` przy próbie użycia np. `file:` lub `ftp:` jako endpointu HTTP.
- Cleartext nadal jest dozwolony tylko dla dokładnych hostów `localhost`, `127.0.0.1` i `10.0.2.2`, bez klucza API i bez credentiali w query.
- Porównanie hostów nie zależy już od locale systemowego.
- Dodano `ProviderEndpointPolicyTest.kt` i rozszerzono test hostów o wariant wielkimi literami.

## Materiały architektoniczne

- Dodano `COMPANION_TRANSPORT_SECURITY_DESIGN_0.6.30_PL.md` (dokument kontynuowany w kolejnych wersjach) z wymaganiami TLS/mTLS, pairingiem, provisionowaniem kluczy, transferem chunków, lifecycle sesji, logowaniem i bramkami sandboxa.
- Roadmapa wskazuje jasno, że HMAC-only nie zapewnia poufności, a TLS/desktop Companion/sandbox nie są jeszcze zaimplementowane.

## Wersja

- `versionCode = 54`
- `versionName = 0.6.28`

## Ograniczenia

- Nie dodano socketów, TLS/mTLS, certyfikatów, discovery, desktop executora ani sandboxa.
- Nie wolno wysyłać bundle ani sekretów przez istniejące ramki HMAC bez poufnego i uwierzytelnionego transportu.
- `PROJECT_TESTS` pozostaje `NOT_RUN`.
