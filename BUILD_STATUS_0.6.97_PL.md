# BUILD STATUS — AI MASTER PRO 0.6.97-SOURCE

## Baza i zakres
- Paczka źródłowa: `AI_MASTER_PRO_0_6_97_CHANGESET_LEDGER_CORRUPTION_GUARD_SOURCE.zip`; rozmiar 886,316 B; `unzip -t` PASS. SHA-256 po finalizacji dokumentacji zostanie zapisany w raporcie wydania.
- Baza: `AI_MASTER_PRO_0_6_96_HOST_EXPLICIT_ACTIVE_CHANGESET_SCOPE_SOURCE.zip`.
- `ChangeSetStore.readResult()` rozróżnia `VALID` i `CORRUPT`; poprawnie pusty rejestr to VALID z 0 wpisów, a pusty/nieparsowalny/oversized ledger, wadliwy rekord, nieznany status, zduplikowane ID lub niepoprawny typ tablicy to CORRUPT.
- Nieznane/niepoprawne `testLevel` i `testStatus` nie są po cichu akceptowane. Historyczny `PROJECT_TESTS` nadal jest downgrade'owany do `LEGACY_UNVERIFIED` + `NOT_RUN`.
- Wszystkie mutacje `propose`, `transition`, `recordSnapshot` i zapisów weryfikacji korzystają z `readOrThrow()`: uszkodzony ledger blokuje zapis, więc nie zostaje nadpisany pustą listą.
- `HostActiveChangeSetStore` czyta `readResult()` i zwraca INVALID przy uszkodzonym ChangeSet ledger; nie może wtedy autoryzować scope MASTER. Rekord active pointer przechowuje też status ChangeSet, a host buduje resolver candidate bez drugiego, potencjalnie nieaktualnego odczytu listy zmian.
- UI Command Center, pamięć i akcja weryfikacji patcha pokazują stan CORRUPT oraz blokadę zamiast prezentować rejestr jako pusty.
- Komunikat błędu odczytu jest sanitizowany do kodu/typu wyjątku; nie ujawnia fragmentów uszkodzonego JSON, który mógłby zawierać dane użytkownika.

## Walidacja wykonana
- `bash tools/verification_resolver_harness/run.sh`: **65/65 PASS**; `kotlinc -Werror` dotyczy jawnie wymienionych klas resolvera/buildera i testów.
- `bash tools/changeset_store_compile_harness/run.sh`: **PASS**; `ChangeSetStore` + `HostActiveChangeSetStore` skompilowane `kotlinc -Werror` z compile-only shimami Android Context/SharedPreferences i org.json. To kontrola kompilacji API/syntax, nie test zachowania Android/JSON/Robolectric.
- Dodano Robolectric/JUnit testy: valid empty ledger, malformed JSON, malformed row, duplicate IDs, brak nadpisania danych po korupcji, invalidation aktywnego scope przy corrupt ledger oraz downgrade historycznego APPLIED/PROJECT_TESTS claim do APPLIED_UNVERIFIED/NOT_RUN.
- Statyczna kontrola: produkcyjne mutacje ChangeSet używają `readOrThrow`; host active store używa `readResult`; UI odróżnia CORRUPT od VALID-empty.

## Ograniczenia — NOT RUN
- Nowe Robolectric/JUnit testy ChangeSetStore: ADDED / NOT RUN.
- Pełny Gradle/JUnit/Robolectric: NOT RUN.
- Android SDK build / assembleDebug / APK / urządzenie: NOT RUN.
- Harness 65/65 nie kompiluje nowych Android/JSON klas `ChangeSetStore` i `HostActiveChangeSetStore`; osobny compile harness potwierdza wyłącznie kompilację tych klas względem shim API, nie runtime parsera ani SharedPreferences.
- `versionName=0.6.54`, `versionCode=80` bez zmian; nie utworzono APK.
- Companion transport pozostaje nieaktywny/BLOCKED.

## Następny krok
- Wprowadzić wspólną blokadę procesu dla importu, apply, rollback i odczytów workspace, ponieważ wiele instancji `ProjectWorkspaceStore` ma obecnie osobne monitory instancji. Zachować transakcyjność katalogu, bez blokowania UI długim I/O.
- Następnie uruchomić nowe testy Robolectric i pełny Gradle, gdy środowisko będzie dostępne.
