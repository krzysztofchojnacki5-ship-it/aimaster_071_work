# 0.6.90-SOURCE — powiązanie dowodów z task/chat/snapshot

- Dodano do `VerificationEvidence` identyfikatory `chatId` i `snapshotId`; `taskId` jest niezależnym identyfikatorem zadania.
- Assessment odrzuca mieszanie rekordów z różnych zadań, czatów i snapshotów.
- `VerificationEvidenceStore.forScope()` zapewnia dokładne filtrowanie trójką zakresu; puste identyfikatory blokują odczyt.
- Ledger V2 zapisuje 10 pól, a parser zachowuje kompatybilność z V1. Legacy records pozostają unscoped; migracja do V2 nie usuwa ich.
- ChangeSet przechowuje chatId, nowe patche wiążą się z bieżącym czatem.
- `ProjectTestRunner` używa taskId z ChangeSet zamiast błędnie podstawiać snapshotId; każdy wynik wiąże task/chat/snapshot.
- Testy: Evidence + Store 17/17 PASS; ProjectTestRunner 3/3 PASS; razem 20/20 lokalnych shim testów.
- Pełny Gradle/Android build NOT RUN; host-generated executionId registry i automatyczna konsumpcja dowodów przez MASTER pozostają kolejnymi etapami.
