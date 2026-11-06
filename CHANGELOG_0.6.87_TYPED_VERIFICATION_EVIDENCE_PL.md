# 0.6.87-SOURCE — typowane dowody weryfikacji

## Problem
Prompt może nakazać uczciwe raportowanie, ale tekst odpowiedzi agenta nadal nie jest dowodem, że test lub build faktycznie wykonano. MASTER potrzebuje rozdzielonego, maszynowo sprawdzalnego statusu dowodów.

## Zmiany
- Dodano typy `VerificationEvidence`, `VerificationEvidenceOrigin`, `VerificationEvidenceStatus`, `VerificationEvidenceKind` i `VerificationAssessment`.
- `AGENT_CLAIM` jest ograniczony do `CLAIM_ONLY`; nie może zostać oznaczony jako PASS.
- `TOOL_EXECUTION` wymaga hostowego `executionId`.
- Ocena wymaga wszystkich wymaganych rodzajów dowodów, FAIL ma pierwszeństwo, NOT_RUN/CLAIM_ONLY nie spełnia wymagania, a dane różnych zadań nie mogą zostać połączone.
- `ProjectTestRunner.Result` zwraca dowody strukturalne; kontrola projektu pozostaje NOT_RUN, dopóki nie ma izolowanego executora.

## Walidacja
`kotlinc -Werror` i lokalne shimy: VerificationEvidence 6/6 PASS; ProjectTestRunner 3/3 PASS. Pełny Gradle/JUnit/Robolectric/Android build NOT RUN.

## Otwarte
Trwała serializacja do `TaskCheckpointStore`, pochodzenie executionId z rzeczywistej warstwy host actions, przekazanie dowodów do MASTER synthesis i UI diagnostyki.
