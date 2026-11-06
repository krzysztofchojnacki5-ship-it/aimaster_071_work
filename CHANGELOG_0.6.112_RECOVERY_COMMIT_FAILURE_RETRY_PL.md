# CHANGELOG — AI MASTER PRO 0.6.112-SOURCE

## Cel
Rozszerzyć fault matrix granicy `activate → invalidate → acknowledge`, szczególnie gdy Android storage zwraca błąd zapisu aktywnego pointera, acknowledge zawodzi po udanej aktywacji albo nie można trwale usunąć pointera po zmianie czatu.

## Zmiany produkcyjne
- `PatchReceiptRecoveryCommitGate.Result` otrzymał osobny `INVALIDATION_FAILED`.
- Gate przechwytuje wyjątek/błąd trwałego unieważnienia i kończy bez acknowledge. Receipt pozostaje do retry; nie jest już błędnie raportowany jako zwykły błąd acknowledge.
- `MainActivity` rozróżnia `ACTIVATION_FAILED`, `INVALIDATION_FAILED` i `ACKNOWLEDGE_FAILED`; każdy przypadek zachowuje receipt, gdy commit nie został bezpiecznie zakończony.

## Testy dodane
- Host harness rozszerzony do 7/7 PASS: trwały pointer po błędzie ack i idempotentny retry; błąd invalidation po chat switch blokuje acknowledge.
- `ChangeSetStoreTest`: dodano 3 testy Robolectric integrujące produkcyjny gate z rzeczywistym `HostActiveChangeSetStore` i wstrzykiwanym storage: active-pointer commit failure + retry, ack failure po durable pointer + retry, invalidation failure po chat switch.
- Testy Robolectric są ADDED / NOT RUN — nie raportować ich jako zaliczonych.

## Walidacja wykonana
- Commit gate host harness: 7/7 PASS (`kotlinc -Werror`, bezpośredni K2JVMCompiler).
- Recovery concurrency: 3/3 PASS + MainActivity static guard PASS.
- Coordinator: 9/9 PASS.
- Protocol: 8/8 PASS.
- Policy: 11/11 PASS.
- Łącznie: 38 hostowych przypadków PASS + statyczny guard.

## Niepotwierdzone
- Filesystem integration, workspace operation lock i verification resolver: ponowne uruchomienia w tej iteracji zakończyły się timeoutem; brak nowych wyników.
- ChangeSetStoreTest / Robolectric: 3 testy dodane, NOT RUN; cały zestaw NOT RUN.
- Gradle/JUnit/Robolectric/Android compile/APK/device/process-death: NOT RUN.

## Stan
- `versionName=0.6.54`, `versionCode=80`; bez APK.
- Companion transport pozostaje BLOCKED.
