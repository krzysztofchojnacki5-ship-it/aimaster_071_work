# Build status — 0.6.87-SOURCE

- Parent: `AI_MASTER_PRO_0_6_86_MASTER_ENGINEERING_PROTOCOL_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: wprowadzić typowane dowody weryfikacji i podłączyć je do bezpiecznego `ProjectTestRunner`, bez awansowania deklaracji agenta do wyniku testu.

## Zmiany

- Dodano `VerificationEvidence.kt`:
  - jawne źródła: `RUNTIME_CHECK`, `TOOL_EXECUTION`, `STATIC_REVIEW`, `AGENT_CLAIM`, `NOT_RUN`;
  - jawne statusy: `PASS`, `FAIL`, `NOT_RUN`, `CLAIM_ONLY`;
  - typy kontroli: integralność workspace/snapshotu, zakres patcha, testy projektu, kompilacja, unit/integration, Robolectric, Android build, device test i OTHER;
  - konstruktor egzekwuje, że `AGENT_CLAIM` może mieć wyłącznie `CLAIM_ONLY`;
  - `TOOL_EXECUTION` wymaga niepustego identyfikatora rzeczywistego wywołania;
  - `NOT_RUN` nie może być przedstawione jako PASS/FAIL.
- Dodano `VerificationAssessment`: wymaga kompletnego zestawu wymaganych rodzajów dowodów, rozstrzyga FAIL przed PASS, traktuje claim/NOT_RUN jako unresolved i odrzuca mieszanie dowodów z różnych taskId.
- `ProjectTestRunner.Result` zwraca teraz strukturalne dowody dla każdej kontroli. `PROJECT_TESTS` pozostaje jawnie NOT_RUN; PASS integralności nie podnosi oceny całości do PASS.
- Dodano testy `VerificationEvidenceTest` i rozszerzono `ProjectTestRunnerTest`.

## Walidacja wykonana

- `VerificationEvidence.kt` + `VerificationEvidenceTest.kt`: `kotlinc -Werror`; lokalny JVM shim runner 6/6 PASS.
- Rzeczywisty `ProjectTestRunner.kt` wraz z zależnościami workspace/patch/scanner/protocol i testami: `kotlinc -Werror`; lokalny Java JUnit shim runner 3/3 PASS.
- Łącznie: 9/9 testów lokalnych PASS.
- Testy potwierdzają: claim nie może być PASS, tool PASS wymaga executionId, niepełny zestaw pozostaje INCOMPLETE, brak dowodów to NOT_RUN, mieszanie tasków jest blokowane, FAIL pozostaje FAIL do rzeczywistego rerun PASS, a ProjectTestRunner nie myli integralności z uruchomieniem kodu projektu.

## NOT RUN / ograniczenia

- Gradle, pełny JUnit, Robolectric, `assembleDebug`, APK, Android Keystore i test na urządzeniu: NOT RUN.
- Dowody są obecnie zwracane z `ProjectTestRunner.Result`; nie zostały jeszcze trwale zapisane w `TaskCheckpointStore` ani podłączone do syntezy MASTER-a. To kolejny etap, nie należy twierdzić, że trwała pamięć dowodów już istnieje.
- Model danych jest warstwą spójności aplikacji, nie granicą bezpieczeństwa przed złośliwym kodem w tym samym procesie. Dowód `TOOL_EXECUTION` musi w przyszłości pochodzić z hostowego rejestru wywołań, a nie z tekstu modelu.
- Companion transport pozostaje BLOCKED.
