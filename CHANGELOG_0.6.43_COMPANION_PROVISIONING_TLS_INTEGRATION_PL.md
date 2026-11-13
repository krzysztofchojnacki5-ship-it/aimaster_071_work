# Changelog — AI MASTER PRO 0.6.43

## Companion: proof-of-possession i integracja zaufania
- Dodano domenowo separowany komunikat challenge dla klucza tożsamości Companion.
- Podpis ECDSA/SHA-256 nie jest już liczony nad surowym challenge; prefiks domenowy oddziela go od innych protokołów.
- Dodano weryfikację podpisu kluczem publicznym i limity wejścia.
- Dodano fabrykę profilu TLS korzystającą z aktualnego snapshotu trwałej mapy peer ID → SPKI.
- Dodano preferowaną ścieżkę budowania `CompanionSessionOwner` z profilem utworzonym przez magazyn provisioningu.
- Pusta mapa zaufania nie może utworzyć profilu TLS.

## Ograniczenia
- Lokalny certyfikat Keystore nie jest certyfikatem TLS z SAN i zaufanym łańcuchem.
- Rotacja/revocation nie zamyka automatycznie już istniejących sesji; aplikacja musi je zamknąć i odtworzyć.
- Migracja PSK, recovery/re-pairing i testy Android Keystore pozostają otwarte.
- Transport nadal zablokowany.
