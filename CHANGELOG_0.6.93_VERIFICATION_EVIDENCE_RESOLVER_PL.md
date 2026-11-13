# 0.6.93 — Verification Evidence Resolver

## Zmiany
- Dodano `VerificationEvidenceResolver` jako jedną, fail-closed bramę wyboru dowodów dla MASTER-a.
- Wymagany jest kompletny zakres `taskId`, `chatId`, `snapshotId` oraz niepusty zbiór wymaganych rodzajów dowodów.
- Resolver korzysta z `VerificationEvidenceStore.forScope()` i dodatkowo sam sprawdza dokładną zgodność zakresu.
- `AGENT_CLAIM` trafia wyłącznie do `agentClaims`; nie uczestniczy w `VerificationAssessment`.
- `TOOL_EXECUTION` jest akceptowany tylko przy zgodnym markerze startowym `NOT_RUN` o tym samym `executionId` i `VerificationEvidenceKind`, w tym samym zakresie, z wcześniejszym timestampem.
- Niepowiązane wyniki narzędzi są odrzucane i liczone w `rejectedUnpairedExecutionCount`.
- Latest-per-kind zachowuje zasadę, że nowy FAIL lub NOT_RUN unieważnia możliwość polegania na starszym PASS.
- `ToolExecutionRegistry` zapisuje executionId również w markerze startowym i w wyniku abandon.

## Testy
- 10 testów resolvera.
- Łącznie 48/48 lokalnych testów shim-JUnit PASS.
- `kotlinc -Werror` PASS.
- Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN.

## Następny krok
Podłączyć resolver do ścieżki MASTER synthesis: przekazać jawny scope aktywnego zadania, zbudować ograniczony kontekst dowodów, a wynik assessment podać do `MasterResponseCheck`. Brak scope lub nieprawidłowy ledger ma blokować twierdzenia o weryfikacji, nie zaś powodować fallback do `forTask()` lub `load()`.
