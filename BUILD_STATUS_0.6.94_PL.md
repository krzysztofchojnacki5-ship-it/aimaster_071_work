# BUILD STATUS — AI MASTER PRO 0.6.94-SOURCE

- Baza: `AI_MASTER_PRO_0_6_93_VERIFICATION_EVIDENCE_RESOLVER_SOURCE.zip`.
- Wersja aplikacji bez zmian: `versionName=0.6.54`, `versionCode=80`.
- Dodano `MasterEvidenceContextBuilder`: buduje ograniczony kontekst wyłącznie dla jednego zestawu zmian jawnie wskazanego przez użytkownika w aktywnym czacie przez `changeSetId`, `taskId` lub `snapshotId`.
- Nie ma fallbacku do najnowszego ChangeSet, do `forTask()` ani do pełnego `load()`. Brak odwołania, wiele dopasowań, brakujące identyfikatory, obcy chatId i uszkodzony ledger nie dostarczają assessmentu MASTER-owi.
- Kontekst przekazano do obu ścieżek MASTER w `MainActivity`: MASTER_ONLY i końcowa synteza zespołu. Assessment trafia również do `MasterResponseCheck`.
- `AGENT_CLAIM` pozostaje tylko informacją; wymagane rodzaje dowodów domyślnie obejmują WORKSPACE_INTEGRITY, SNAPSHOT_INTEGRITY, PATCH_SCOPE i PROJECT_TESTS.
- Dodano pięć testów buildera: dokładny wybór zakresu, brak fallbacku, izolacja czatu, odrzucenie wielu referencji i brak syntetyzowania brakującego scope.
- Lokalny shim-JUnit: **57/57 PASS** (w tym resolver, builder, registry, store, adapter i MasterResponseCheck).
- `kotlinc -Werror` dla zestawu core/testów: PASS przez `tools/verification_resolver_harness/run.sh`.
- Statyczna kontrola call sites: oba wywołania `MasterResponseCheck.evaluate` w ścieżkach MASTER otrzymują assessment; oba prompty zawierają ograniczony kontekst dowodów.
- Archiwum bazowe 0.6.93: SHA-256 zgodny z rejestrem; `unzip -t` PASS.

## Ograniczenia / NOT RUN
- Pełny Gradle/JUnit/Robolectric: NOT RUN.
- Android SDK build / `assembleDebug` / APK: NOT RUN.
- Test urządzeniowy: NOT RUN.
- Automatyczny wybór aktywnego ChangeSet z kontekstu orkiestratora nie jest jeszcze wdrożony. W 0.6.94 użytkownik musi jawnie wskazać identyfikator zestawu zmian/zadania/snapshotu w treści promptu; to ogranicza ryzyko przypięcia dowodów do niewłaściwego zadania.
- Rejestr lokalny nie jest kryptograficzną atestacją przeciw zmodyfikowanemu procesowi aplikacji lub danym urządzenia.
- Companion transport pozostaje nieaktywny.
