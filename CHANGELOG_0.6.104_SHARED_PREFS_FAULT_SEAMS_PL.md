# AI MASTER PRO 0.6.104-SOURCE — testowalna warstwa SharedPreferences i ścieżki awarii

## Cel

Po wydzieleniu polityki recovery w 0.6.103 następną luką była niemożność deterministycznego zasymulowania błędów trwałego storage bez prawdziwego Android runtime. Ten etap dodaje wąski seam storage dla ledgeru ChangeSet i aktywnego zakresu hosta.

## Zmiany

- Dodano `StringPreferenceStore` z operacjami `read`, `write`, `remove` oraz adapter `AndroidStringPreferenceStore`. Adapter pobiera SharedPreferences leniwie, dzięki czemu wyjątek z `getSharedPreferences()` również trafia do obsługi ścieżki odczytu.
- `ChangeSetStore` przyjmuje opcjonalny backend storage; domyślnie zachowuje dotychczasowe nazwy plików/preferences i klucze.
- Odczyt ledgeru nadal klasyfikuje wyjątki i uszkodzony JSON jako `CORRUPT`; mutacje odmawiają zapisu w takim stanie. `commit()==false` nadal kończy mutację wyjątkiem, nie zwraca pozornego sukcesu.
- `HostActiveChangeSetStore` przyjmuje osobny backend; wyjątki odczytu zwracają `INVALID`, a błędy commit/remove zwracają `false`. Nieudany commit nie publikuje aktywnego scope.
- Dodano osiem testów Robolectric obejmujących read exception, corrupt JSON, `commit()==false`, commit exception oraz nieudane remove.
- Istniejące wywołania Kotlin zachowują domyślny adapter Android; brak migracji danych i zmiany formatu ledgeru.

## Walidacja wykonana

- `tools/changeset_store_compile_harness/run.sh`: **PASS**, `kotlinc -Werror` dla obu store'ów i adaptera na compile-only Android/JSON shims.
- `tools/workspace_transaction_recovery_harness/run.sh`: **12/12 PASS**.
- `tools/patch_receipt_policy_harness/run.sh`: **11/11 PASS**.
- `tools/verification_resolver_harness/run.sh`: **73/73 PASS**.

## Nie wykonano

- Osiem nowych testów Robolectric: **NOT RUN**.
- Gradle/JUnit, pełny Android compile, APK, urządzenie: **NOT RUN**.
- Testy commit/read failure są dodane do zestawu Robolectric, ale nie wolno traktować ich jako zaliczonych do czasu uruchomienia.
- Companion transport pozostaje **BLOCKED**.
- `versionName=0.6.54`, `versionCode=80`; nie tworzono APK.

## Następny etap

Uruchomić rzeczywiste testy Robolectric po uzyskaniu działającego Gradle runtime; potem przetestować startup recovery przy każdym punkcie przerwania i współbieżność z importem oraz zmianą czatu.
