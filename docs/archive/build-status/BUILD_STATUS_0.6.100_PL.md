# BUILD STATUS — AI MASTER PRO 0.6.100-SOURCE

- Baza: 0.6.99-SOURCE, bez zmiany wersji aplikacji.
- Paczka: `AI_MASTER_PRO_0_6_100_PATCH_TRANSACTION_COMPENSATION_SOURCE.zip`.
- `versionName=0.6.54`, `versionCode=80`; APK nie utworzono.

## Zmiany i naprawy
- Preview patcha przechowuje dokładną tożsamość manifestu workspace i ID czatu źródłowego.
- Po recenzji i drugim potwierdzeniu patch jest stosowany wyłącznie, jeśli workspace i czat nadal odpowiadają wartościom z chwili preview. Kontrola workspace identity, integralności i `applyPreview()` jest wykonywana pod jedną blokadą.
- Przełączenie czatu w trakcie recenzji nie może przypisać patcha do nowej rozmowy.
- Rozszerzono obsługę częściowo zakończonego przepływu apply → recordSnapshot → APPLIED_UNVERIFIED → active pointer.
- Jeżeli zapis rejestru nie powiedzie się po zastosowaniu patcha, aplikacja podejmuje kompensacyjny rollback tylko przy niezmienionej tożsamości workspace; następnie próbuje uzgodnić ledger do `ROLLED_BACK` albo `REJECTED`.
- Jeśli status `APPLIED_UNVERIFIED` został już trwale zapisany, a zawiedzie jedynie aktywacja scope, patch pozostaje zastosowany i raport jasno wskazuje brak aktywnego zakresu; nie wykonuje się nieuzasadnionego rollbacku.
- `ProjectWorkspaceStore.applyPreview()` przy błędzie po podmianie próbuje przywrócić zweryfikowany snapshot bez tworzenia kolejnej kopii awaryjnej. Snapshot bazowy pozostaje zachowany aż do przejścia kontroli identity po podmianie.
- Pruning snapshotów zachowuje jawnie wskazany snapshot kompensacyjny.
- Dziennik aktywności i raport operacji rozróżniają: patch zastosowany, patch wycofany, rollback zablokowany oraz nieudaną aktywację scope.

## Walidacja wykonana
- Resolver/lock harness: **73/73 PASS**, `kotlinc -Werror`.
- ChangeSet/HostActive store compile shim: PASS, `kotlinc -Werror`.
- Workspace core compile: PASS, `kotlinc -Werror` dla `BundleContentScanner`, `ExecutorProtocol`, `ChangeSafetyGuard`, `ProjectPatchEngine`, `ProjectWorkspaceStore`, `ProjectWorkspaceIdentityGuard`.
- Lokalny JVM smoke: PASS — import → preview → apply → snapshot restore → stale preview identity rejection.
- `unzip -t`: PASS po utworzeniu paczki.

## Nie wykonano
- Gradle/JUnit/Robolectric, pełny Android build, APK i testy urządzeniowe — NOT RUN.
- Companion transport — nadal BLOCKED.

## Granice
- Smoke workspace to niezależny JVM test przepływu core, nie test Android UI.
- Nie wykonano fault injection rzeczywistego I/O dla wszystkich punktów awarii SharedPreferences/filesystem; obsługa kompensacji wymaga dalszego testu integracyjnego w Robolectric/Android.
