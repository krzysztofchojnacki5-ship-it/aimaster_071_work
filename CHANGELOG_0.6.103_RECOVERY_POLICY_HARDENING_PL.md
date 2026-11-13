# AI MASTER PRO 0.6.103-SOURCE — wydzielenie polityki recovery i testy idempotencji

## Cel etapu

0.6.102 zapewniało trwały receipt transakcji workspace i startup reconciliation. W 0.6.103 oddzielono decyzje uzgadniania stanu od Androidowego `MainActivity`, aby można je było deterministycznie testować na JVM i ograniczyć ryzyko rozjazdu między przypadkami rollback/commit.

## Zmiany

- Dodano `PatchReceiptRecoveryPolicy` — czysty moduł Kotlin bez zależności Android/SharedPreferences.
- Polityka rozstrzyga na podstawie receipt flag, bieżącej i bazowej workspace identity oraz statusu ChangeSet:
  - czy patch jest skutecznie wycofany,
  - czy trzeba odtworzyć brakujące powiązanie snapshotu,
  - jaki status ledger należy zapisać,
  - czy trzeba unieważnić aktywny scope,
  - czy ChangeSet może być kandydatem do odtworzenia active pointer.
- `MainActivity.recoverPendingPatchReceiptAtStartup()` korzysta z tej polityki; walidacja snapshotu, ledger, blokady workspace, aktywacji pointera i końcowego ack pozostaje po stronie hosta.
- Kolejność operacji zachowana: snapshot integrity → ledger read → ewentualne przypięcie snapshotu → status transition → rollback invalidation / aktywacja scope → receipt acknowledgement.
- Dodano `tools/patch_receipt_policy_harness/` z przypadkami awarii przed/po zapisie snapshotu, statusu, active pointer i receipt ack, scenariuszami rollback oraz walidacją fail-closed.

## Walidacja lokalna

- `tools/patch_receipt_policy_harness/run.sh`: **11/11 PASS** (`kotlinc -Werror`, JVM).
- `tools/workspace_transaction_recovery_harness/run.sh`: **12/12 PASS**.
- `tools/verification_resolver_harness/run.sh`: **73/73 PASS**.
- `tools/changeset_store_compile_harness/run.sh`: **PASS** (`kotlinc -Werror` z compile-only Android/JSON shims).
- Statyczna kontrola integracji MainActivity: **8/8 PASS** (wywołanie startup, lock, kolejność walidacji, polityka, aktywacja i ack).

## Czego te testy NIE potwierdzają

- Harness polityki testuje czystą logikę decyzyjną, nie prawdziwy Android `SharedPreferences` ani pełny lifecycle Activity.
- Statyczna kontrola MainActivity nie jest kompilacją Android.
- Gradle/JUnit/Robolectric, pełny Android compile, APK i testy urządzeniowe: **NOT RUN**.
- Companion transport pozostaje **BLOCKED** i nie jest automatycznie uruchamiany.

## Następny krok

Dodać testy awarii warstwy trwałego storage (commit=false, wyjątek odczytu, uszkodzony JSON), a następnie — gdy środowisko Gradle będzie dostępne — uruchomić rzeczywiste Robolectric startup recovery. Rozszerzyć testy granic transakcji i potwierdzić brak wyścigów z importem/czatami.
