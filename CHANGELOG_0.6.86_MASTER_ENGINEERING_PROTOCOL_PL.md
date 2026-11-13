# 0.6.86-SOURCE — protokół pracy inżynierskiej MASTER-a

## Problem
Dotychczasowy `AgentPrompts.systemPrompt()` zawierał bezwarunkową instrukcję, że agent ma pełny dostęp do całego repozytorium, Gradle, manifestu i wszystkich plików. Była sprzeczna z zasadą prawdziwego raportowania i mogła powodować halucynacje dostępu.

## Zmiany
- Usunięto fałszywą deklarację pełnego dostępu.
- Dodano warunkowy kontrakt dostępu: tylko dane faktycznie udostępnione w bieżącej sesji.
- Dodano wspólny protokół inżynierski dla ról technicznych.
- Dodano protokół MASTER-a dotyczący orkiestracji, weryfikacji agentów, pamięci z pochodzeniem i self-checku.
- Dodano testy promptów i osobny playbook opisujący zasady przyszłego runtime.

## Walidacja
`kotlinc -Werror` + lokalny shim runner: 4/4 testy PASS. Gradle/JUnit/Robolectric/Android build NOT RUN.

## Następny etap
Przenieść stan dowodów z promptu do kodu: typowane `VerificationEvidence`, kryteria akceptacji, statusy PASS/FAIL/NOT_RUN, checkpointy i pochodzenie wpisów pamięci. Nie traktować samej deklaracji modelu jako dowodu wykonania.
