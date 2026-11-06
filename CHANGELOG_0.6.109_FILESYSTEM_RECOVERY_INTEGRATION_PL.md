# CHANGELOG — AI MASTER PRO 0.6.109-SOURCE

## Cel
Połączyć produkcyjny `PatchReceiptRecoveryCoordinator` z rzeczywistym `ProjectWorkspaceStore` na tymczasowym filesystemie, zamiast testować oba elementy wyłącznie osobno.

## Zmiany
- Dodano `tools/patch_receipt_filesystem_integration_harness`, który kompiluje bezpośrednio produkcyjne klasy: `ProjectWorkspaceStore`, `ProjectPatchEngine`, `PatchReceiptRecoveryPolicy` i `PatchReceiptRecoveryCoordinator`.
- Harness wykonuje prawdziwy import ZIP, preview patcha, apply, utworzenie snapshotu, durable receipt, weryfikację snapshotu, recovery i acknowledge na katalogach tymczasowych.
- Dodano scenariusze: pełne uzgodnienie + ponowne idempotentne recovery; błąd przejścia ledgeru po zapisaniu snapshotu i retry; niezgodność czatu bez mutacji ledgeru; rollback żądany i odtworzony na prawdziwym workspace.
- Backend ledgeru jest kontrolowanym adapterem testowym, nie Android SharedPreferences. Testy potwierdzają rzeczywisty filesystem workspace, ale nie Android process death.

## Walidacja
- filesystem integration: 5/5 PASS (`kotlinc -Werror`, produkcyjny workspace store + coordinator).
- coordinator: 9/9 PASS.
- protocol: 8/8 PASS.
- policy: 11/11 PASS.
- workspace transaction: 12/12 PASS.
- workspace lock: 5/5 PASS.
- verification resolver: 73/73 PASS.
- ChangeSet/HostActive compile shim: PASS.
- Gradle/JUnit/Robolectric/Android build/APK/device: NOT RUN.

## Ograniczenia i następne kroki
- Nie udowodniono jeszcze atomowości całej sekwencji Android `MainActivity` + SharedPreferences + import/chat switch.
- Dodano test przeplotu: import rzeczywistego ZIP czeka na zakończenie recovery, gdy oba procesy używają produkcyjnego `ProjectWorkspaceOperationLock` (5/5 PASS). Audyt statyczny potwierdził, że jedyny call site `importZip` w MainActivity oraz `applyPreview` są objęte wspólnym lockiem. Następny etap: testy chat switch/identity race i kontrola pełnego Android startup.
- `versionName=0.6.54`, `versionCode=80`; bez APK.
- Companion transport pozostaje BLOCKED.
