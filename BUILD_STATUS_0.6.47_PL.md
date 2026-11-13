# AI MASTER PRO 0.6.47 — status wydania

## Zakres
Punkt 5.1c: generowanie CSR dla leafa TLS Companion.

## Zmienione / nowe pliki
- `app/src/main/java/pl/aimasterpro/app/core/CompanionCsrBuilder.kt` (nowy)
- `app/src/test/java/pl/aimasterpro/app/core/CompanionCsrBuilderTest.kt` (nowy, 7 testów)
- `docs/tools/csr_proto.py` (prototyp kodera, dowód algorytmu)
- `app/build.gradle.kts` (versionCode 73, versionName 0.6.47)
- `CHANGELOG_0.6.47_TLS_CSR_BUILDER_PL.md`, `BUILD_STATUS_0.6.47_PL.md`, `AGENT_HANDOFF_PROMPT_PL.md`

## Walidacja
- Prototyp Python + `openssl req -verify`: **PASS** (podpis OK, odczyt SAN/KU/EKU poprawny, zmodyfikowany CSR odrzucony).
- Nawiasy w plikach Kotlin: **PASS** (kontrola tekstowa).
- Kompilacja Kotlin i testy JVM: **NOT RUN**.
- Gradle, Robolectric, Android Keystore, APK: **NOT RUN**.

## Otwarte
- Akceptacja decyzji DER ręcznie zamiast zależności.
- Składanie łańcucha w Keystore: do potwierdzenia na urządzeniu.
- Pkt 5.1d (zapis i integracja z Keystore) i pozostałe pkt 2–5 listy przekazania.
