# CHANGELOG — AI MASTER PRO 0.6.107-SOURCE

## Zakres
Pochodna `AI_MASTER_PRO_0_6_106_WORKSPACE_LOCK_CONCURRENCY_HARDENING_SOURCE.zip`. Wersja aplikacji pozostaje `versionName=0.6.54`, `versionCode=80`.

## Nowości
- Dodano `tools/patch_receipt_protocol_harness`, hostowy model kolejności trwałych kroków startup reconciliation.
- Dodano fault injection po zapisie snapshotu, zmianie statusu, unieważnieniu scope oraz zapisie active pointera.
- Dodano test wznowienia rollbacku po częściowej aktualizacji ledgeru.
- Dodano testy izolacji: późniejsza identity workspace nie może zostać aktywowana; zmiana czatu pozwala uzgodnić ledger, ale nie przenosi aktywnego scope do innego czatu.
- Dodano test no-op po potwierdzeniu receipt.
- Harness korzysta z produkcyjnej `PatchReceiptRecoveryPolicy`; modeluje trwałe punkty zapisu, ale nie zastępuje Robolectric ani testu rzeczywistego `MainActivity`.

## Wyniki
- Patch receipt protocol fault matrix: **8/8 PASS**.
- Patch receipt recovery policy: **11/11 PASS**.
- Workspace transaction recovery: **12/12 PASS**.
- Workspace operation lock: **5/5 PASS**.
- Verification resolver/evidence: **73/73 PASS**.
- ChangeSet/HostActive compile shim: **PASS** (`kotlinc -Werror`).
- ZIP integrity: **PASS** (`unzip -t`).

## Ograniczenia
- Pełny Gradle/JUnit/Robolectric/Android compile/APK/device: **NOT RUN**.
- Nowy protocol harness to host JVM fault model, nie dowód zachowania SharedPreferences/Android lifecycle.
- Companion transport pozostaje **BLOCKED**.
