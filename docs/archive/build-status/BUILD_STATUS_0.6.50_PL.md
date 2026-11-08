# AI MASTER PRO 0.6.50 — status wydania

## Zakres
- Punkt 2 (przechowywanie PSK, model przejściowy).
- Naprawa `SecureStore.key()` (usuwanie klucza przy wyjątku) i obsługi błędu w `saveProvider`.

## Zmienione / nowe pliki
- `core/CompanionPskRepository.kt` (nowy)
- `data/AndroidPskCipher.kt` (nowy, NOT RUN)
- `data/AndroidTlsChainBlobStore.kt` (sparametryzowany)
- `data/SecureStore.kt` (poprawka key/putSecret/saveConfig)
- `MainActivity.kt` (obsługa błędu w saveProvider)
- `test/core/CompanionPskRepositoryTest.kt` (nowy, 9 testów)
- `app/build.gradle.kts` (versionCode 76, versionName 0.6.50)
- `CHANGELOG_0.6.50_...`, `BUILD_STATUS_0.6.50_PL.md`, `AGENT_HANDOFF_PROMPT_PL.md`

## Walidacja
- Zrównoważenie nawiasów we wszystkich zmienionych plikach: **PASS** (kontrola tekstowa).
- Przegląd testów względem kodu (kody błędów, AAD, nadpisywanie, granice): **PASS na papierze**.
- Kompilacja Kotlin i testy JVM: **NOT RUN**.
- `AndroidPskCipher`, `SecureStore` na urządzeniu (w tym brak utraty kluczy API): **NOT RUN**.

## Otwarte
- Decyzje z projektu PSK nie zostały potwierdzone (założenia w CHANGELOG).
- `replaceAllConfigs` ignoruje błędy zapisu (import).
- Rollback całego magazynu; usuwanie rekordu PSK.
- Transport CA i handshake z kluczem Keystore — zablokowane do zamknięcia punktu 5.
