# Changelog — AI MASTER PRO 0.6.46

## Punkt 5.1b — walidator odpowiedzi CA dla certyfikatu TLS
- Dodano `CompanionTlsLeafPolicy`: czysta funkcja bez Androida i bez I/O. Sprawdza: brak CA w leafie, brak self-signed, zgodność klucza z kluczem Keystore, ważność, limit żywotności (domyślnie 30 dni), KeyUsage digitalSignature, wymagane EKU, SAN typu DNS dokładnie zgodne z zatwierdzonymi nazwami (bez wildcardów i dodatkowych typów SAN), łańcuch do zaufanego roota oraz poprawność wystawców (CA, certSign, ważność).
- Każde odrzucenie zwraca stabilny kod (np. `UNTRUSTED_ROOT`, `SAN_MISMATCH`, `KEY_MISMATCH`).
- Dodano `CompanionTlsLeafPolicyTest` z 12 przypadkami na fixturach z `companion-tls/`.

## Ustalenia z fixtur (openssl)
- Wszystkie fixtury są ważne 10 lat, więc przekraczają limit 30 dni. Testy pozytywne podają jawnie wyższy limit, a test negatywny sprawdza domyślny.
- Fixtury nie mają klucza roota, więc nie generowano nowych certyfikatów.

## Granice
- Nic nie zostało skompilowane ani uruchomione (NOT RUN).
- Walidator nie generuje CSR (to pkt 5.1c) i nie zapisuje certyfikatu w Keystore.
- Decyzja o generowaniu CSR pozostaje otwarta; ten krok jej nie wymagał.
