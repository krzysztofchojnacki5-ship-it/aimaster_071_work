# AI MASTER PRO 0.6.105-SOURCE — jawny storage ledgeru dla aktywnego zakresu hosta

## Cel

Audyt 0.6.104 ujawnił niedomkniętą granicę testowalności: `HostActiveChangeSetStore` przyjmował wstrzykiwany backend aktywnego pointera, ale podczas walidacji nadal czytał ChangeSet ledger z domyślnych SharedPreferences. Test mógł więc kontrolować zapis pointera, lecz nie źródło autorytatywnego ledgeru. To utrudniało deterministyczne testowanie korupcji/awarii ledgeru i mogło maskować błędy integracyjne.

## Zmiany

- `ChangeSetStore.PREFERENCES_NAME` jest teraz wewnętrzną stałą współdzieloną z `HostActiveChangeSetStore`; nazwa produkcyjnego pliku SharedPreferences pozostaje bez zmian.
- `HostActiveChangeSetStore.setActive()` i `readActive()` przyjmują dodatkowy, opcjonalny `ledgerStorage`. Domyślnie wskazuje on dokładnie ten sam produkcyjny ledger co dotychczas; istniejące wywołania zachowują kompatybilność.
- Walidacja aktywnego zakresu używa teraz przekazanego backendu ledgeru, zamiast po cichu omijać seam i czytać domyślne SharedPreferences.
- Dodano dwa testy Robolectric: uszkodzony wstrzyknięty ledger blokuje publikację pointera bez próby zapisu; wyjątek odczytu wstrzykniętego ledgeru powoduje `INVALID` i nie usuwa aktywnego pointera.
- Nie zmieniono formatu JSON, nazw kluczy, state machine ani zachowania produkcyjnego przy domyślnych argumentach.

## Walidacja wykonana

- `tools/changeset_store_compile_harness/run.sh`: **PASS**, `kotlinc -Werror` na compile-only Android/JSON shims.
- `tools/patch_receipt_policy_harness/run.sh`: **11/11 PASS**.
- `tools/workspace_transaction_recovery_harness/run.sh`: **12/12 PASS**.
- `tools/verification_resolver_harness/run.sh`: **73/73 PASS** (uruchomione osobno po przekroczeniu limitu czasu w sekwencji zbiorczej).

## Nie wykonano

- Łącznie 10 testów Robolectric w `ChangeSetStoreTest` pozostaje **NOT RUN**; dwa nowe testy są dodane, niezaliczone.
- Gradle/JUnit, pełny Android compile, APK, testy na urządzeniu: **NOT RUN**.
- Nie deklarujemy walidacji rzeczywistego `SharedPreferences` na urządzeniu.
- Companion transport pozostaje **BLOCKED**.
- `versionName=0.6.54`, `versionCode=80`; nie tworzono APK.

## Następny etap

Dodać host-side coordinator testy integracyjne wokół startup receipt recovery i storage fault matrix: błędy kolejnych mutacji ledgeru, powtórne uruchomienie recovery, import/chat switch w wyścigu z recovery oraz dokładne zachowanie pending receipt przy awarii persystencji. Następnie uruchomić Robolectric/Gradle, gdy środowisko będzie dostępne.
