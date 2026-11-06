# Changelog — AI MASTER PRO 0.6.50

## Punkt 2 — przechowywanie PSK parowania (model przejściowy)
- `CompanionPskRepository`: PSK 32 bajty generowany na urządzeniu (SecureRandom), zaszyfrowany AES-GCM z AAD `AI_MASTER_COMPANION_PSK_V1|<peerId>|<version>`. Ponowne parowanie odrzucone (`ALREADY_PROVISIONED`), uszkodzony magazyn nie jest nadpisywany (`STORE_CORRUPT`), błąd zapisu zgłaszany (`WRITE_FAILED`), zmiana peera wykrywana (`PEER_MISMATCH`).
- `AndroidPskCipher` (`data/`): AES-GCM z kluczem Keystore nieeksportowalnym; klucz tworzony tylko przy braku. **NOT RUN**.
- `AndroidTlsChainBlobStore` sparametryzowany nazwą preferencji i kluczem. PSK używa osobnego blobu (`companion_psk_v1`), nigdy wspólnego z łańcuchem TLS.

## Naprawa widocznego błędu w `SecureStore` (poza Companion)
- Problem: `key()` przy DOWOLNYM wyjątku usuwał klucz Keystore i tworzył nowy. Przejściowy błąd niszczył trwale zapisane klucze API dostawców.
- Poprawka: klucz jest tworzony tylko przy braku wpisu; istniejący nigdy nie jest usuwany. Błędy są zgłaszane.
- `putSecret` i `saveConfig` zwracają `Boolean`; przy błędzie nic nie jest zapisywane.
- `MainActivity.saveProvider` pokazuje komunikat, zamiast cicho gubić konfigurację (wcześniej brak obsługi wyjątku groziłby awarią).

## Założenia podjęte bez odpowiedzi użytkownika (do potwierdzenia)
- Legacy PSK: przyjęto brak istniejącego PSK (zgodnie z analizą kodu). Scenariusze S1–S3 z projektu NIE są zaimplementowane.
- Model: przejściowy (surowe PSK w pamięci, zaszyfrowane at-rest). Model HMAC w Keystore (2b) nie wdrożony.

## Znane luki (otwarte)
- `replaceAllConfigs` (import) nadal ignoruje wynik `saveConfig`; częściowy import przy błędzie Keystore pozostaje możliwy.
- Brak operacji usunięcia rekordu PSK w repozytorium (interfejs blob nie ma delete).
- Brak ochrony przed rollbackiem całego magazynu.

## Granice
- Kod Kotlin nie został skompilowany ani uruchomiony (NOT RUN). Testy `CompanionPskRepositoryTest` (9 testów) na JCE AES-GCM — NOT RUN.
- Wersja 0.6.50, versionCode 76.
