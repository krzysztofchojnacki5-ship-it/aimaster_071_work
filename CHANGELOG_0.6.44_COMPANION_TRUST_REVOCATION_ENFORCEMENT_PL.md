# Changelog — AI MASTER PRO 0.6.44

## Egzekwowanie revocation i aktualnego stanu pinów
- Dodano wymagany walidator zaufania peer do `CompanionSessionOwner`; konstruktor ograniczono do modułu aplikacji, aby nie tworzyć sesji bez jawnej polityki walidacji.
- Ścieżka `CompanionProvisioningStore.createSessionOwner()` przekazuje walidator, który sprawdza aktualny rekord peer w zaszyfrowanym magazynie.
- Aktywacja odrzuca receipt dla już unieważnionego peera.
- Aktywna sesja jest ponownie walidowana przed przyjęciem ramki, rozpoczęciem/zakończeniem transferu, odpytywaniem timeoutów i odczytem wewnętrznej tożsamości TLS.
- Brak zaufania, brak rekordu, błąd odczytu magazynu lub wyjątek walidatora powoduje fail-closed: receiver zostaje zamknięty, a sesja przechodzi do `FAILED`.
- Dodano testy regresyjne dla unieważnienia przed aktywacją i po aktywacji.

## Granice
- Unieważnienie jest egzekwowane przy następnej operacji API sesji. Owner nie zarządza jeszcze socketem TLS, więc nie zamyka go natychmiast w momencie revocation.
- Nie dodano transportu ani transferu danych.
- Certyfikat TLS/SAN, migracja PSK, recovery/re-pairing i testy na Android Keystore pozostają otwarte.
