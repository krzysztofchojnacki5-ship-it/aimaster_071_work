# AI MASTER PRO 0.6.49 — status wydania

## Zakres
Punkt 5.1e: zapis i odczyt łańcucha publicznego leafa TLS z ponowną walidacją przy odczycie.

## Nowe pliki
- `app/src/main/java/pl/aimasterpro/app/core/CompanionTlsChainRepository.kt` (kodek, repozytorium, interfejs blob store)
- `app/src/main/java/pl/aimasterpro/app/data/AndroidTlsChainBlobStore.kt` (NOT RUN)
- `app/src/test/java/pl/aimasterpro/app/core/CompanionTlsChainRepositoryTest.kt` (13 testów)
- `app/build.gradle.kts` (versionCode 75, versionName 0.6.49)
- `CHANGELOG_0.6.49_TLS_CHAIN_PERSISTENCE_PL.md`, `BUILD_STATUS_0.6.49_PL.md`, `AGENT_HANDOFF_PROMPT_PL.md`

## Walidacja
- Zrównoważenie nawiasów w nowych plikach: **PASS** (kontrola tekstowa).
- Przegląd logiki testów względem zachowania kodu (kody błędów, granice rozmiarów, zapis przy uszkodzeniu): **PASS na papierze**.
- Kompilacja Kotlin i testy JVM: **NOT RUN**.
- `AndroidTlsChainBlobStore` na urządzeniu: **NOT RUN**.

## Otwarte
- Ochrona przed rollbackiem całego magazynu (licznik zakotwiczony sprzętowo).
- Transport CA (zablokowany do zamknięcia bramek punktu 5).
- Użycie zapisanego klucza Keystore w handshake TLS: do potwierdzenia na urządzeniu.
- Pkt 2 (PSK), 3 (recovery/re-pairing), 4 (własność socketu) listy przekazania.
