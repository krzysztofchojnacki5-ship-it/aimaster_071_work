# AI MASTER PRO 0.6.17 — Patch Peer Review Foundation

## Nowe elementy
- Dodano specyfikację `docs/MASTER_MEMORY_ARCHITECTURE_PL.md`, która mapuje istniejące magazyny pamięci i definiuje bezpieczną ścieżkę do 0.7.0 bez dublowania store-ów.
- Dodano `PatchPeerReviewEngine` — deterministyczny audyt ryzyka zaakceptowanego preview unified diff.
- Wykrywane są m.in. potencjalne sekrety, dynamiczne wykonywanie kodu, mostki JavaScript WebView, zmiany uprawnień/komponentów Androida, zależności builda, usuwanie plików krytycznych i duże zmiany produkcyjne.
- Generator promptu przekazuje recenzentowi dokładny diff, ścieżkę, operację, bazowy i wynikowy SHA-256 oraz wymaganie dowodów i testów. Diff powyżej 80 KB jest blokowany, aby recenzent nie oceniał niepełnego materiału.
- Agregator parsuje ścisły werdykt `APPROVE`, `REQUEST_CHANGES` lub `REJECT`, deduplikuje recenzentów i wykrywa sprzeczne oceny.
- Nawet dwa pozytywne werdykty dają wyłącznie `READY_FOR_HUMAN_APPROVAL`; nie uruchamiają zapisu.
- Centrum dowodzenia prezentuje audyt ryzyka i pozwala skopiować prompt recenzji.

## Bezpieczeństwo i granice
- Moduł nie wykonuje diffu, nie zapisuje workspace i nie uruchamia kodu.
- Lokalne heurystyki są sygnałami do weryfikacji, a nie pełną analizą semantyczną ani dowodem braku podatności.
- Automatyczne wywołanie API recenzentów i trwały rejestr wyników pozostają do integracji w kolejnym podetapie.

## Weryfikacja
- Kompilacja czystego Kotlin/JVM `ProjectPatchEngine` + `PatchPeerReviewEngine` z lekkimi interfejsami zastępczymi dla warstwy Android — PASS.
- Testy `PatchPeerReviewEngineTest` w lokalnym runnerze JUnit-compatible — 13/13 PASS (lekki stub JUnit, bez Gradle).
- `./gradlew testDebugUnitTest --offline` — NOT RUN: Wrapper nie ma lokalnej dystrybucji Gradle 8.13 i próba pobrania zakończyła się `UnknownHostException: services.gradle.org`.
- Pełna kompilacja Android UI i APK — NOT RUN (brak Android SDK / niedostępna dystrybucja Gradle).
