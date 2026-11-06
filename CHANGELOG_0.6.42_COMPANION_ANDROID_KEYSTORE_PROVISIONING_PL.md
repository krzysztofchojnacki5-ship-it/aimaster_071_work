# Changelog 0.6.42 — Companion Android Keystore provisioning

- Dodano `CompanionPeerPinSet`, niezmienny model current/next SPKI z ograniczonym etapowaniem, jawnym anulowaniem i promocją.
- Dodano testy polityki rotacji i odrzucania niejawnego nadpisania staged pin.
- Dodano `CompanionProvisioningStore`:
  - generowanie klucza EC P-256 w Android Keystore;
  - podpisywanie challenge ECDSA/SHA-256 bez eksportu klucza prywatnego;
  - generowanie klucza AES-256-GCM w Android Keystore;
  - szyfrowanie i uwierzytelnianie mapy peer ID → current/next SPKI z AAD;
  - synchroniczne potwierdzenie zapisu;
  - fail-closed przy utracie klucza do istniejących danych;
  - provision, stage, promote, cancel i revoke peer.
- Wersja aplikacji: `versionCode=68`, `versionName=0.6.42`.

## Świadomie niewdrożone
- Brak gotowego certyfikatu TLS z SAN; certyfikat Keystore nie jest automatycznie poprawnym certyfikatem endpointu.
- Brak integracji provisioningu z profilem TLS i właścicielem sesji.
- Brak migracji PSK i odzyskiwania/re-pairing.
- Brak transportu, Desktop Companion, OS sandbox i uruchamiania kodu importowanego.
