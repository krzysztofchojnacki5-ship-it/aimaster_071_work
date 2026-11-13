# 0.6.95 — Host-owned Active Evidence Scope

## Cel
Usunąć zależność wyboru dowodów od identyfikatorów wpisanych przez użytkownika w prompt. Zakres ma pochodzić z danych ChangeSetStore kontrolowanych przez aplikację, bez heurystyki „najnowszy wpis”.

## Zmiany
- Dodano `HostActiveEvidenceScopeResolver`, który wybiera ChangeSet wyłącznie z aktywnego czatu, o statusie `APPLIED_UNVERIFIED` lub `APPLIED` i z niepustym snapshotId.
- Wybór następuje tylko wtedy, gdy istnieje dokładnie jeden kwalifikujący się ChangeSet. Zero kandydatów daje NONE, wiele daje AMBIGUOUS; recency nie rozstrzyga konfliktu.
- Brakujące taskId/changeSetId w rekordzie aktywnego czatu daje INVALID. Rekordy z innych czatów są ignorowane.
- `MasterEvidenceContextBuilder.buildForHostScope()` przyjmuje zakres wybrany przez hosta i nadal waliduje chat/task/snapshot oraz korzysta z exact-scope resolvera.
- Obie ścieżki MASTER w `MainActivity` korzystają teraz z hostowego wyboru. Tekst promptu nie steruje wyborem dowodów.
- Dotychczasowa metoda jawnej referencji pozostaje dla kompatybilności i testów; call sites MASTER korzystają z hostowego API.
- Brak automatycznego wyboru przy wielu zmianach: przyszły UI/orkiestrator powinien jawnie utrzymywać aktywny ChangeSet zamiast używać „najnowszego”.

## Testy
- Dodano testy: wybór pojedynczej zastosowanej zmiany, odrzucenie wielu kwalifikujących się zmian, izolacja czatów, brak snapshotu i malformed candidate.
- Dodano testy hostowego buildera: bez identyfikatora w prompt oraz odrzucenie kandydata z obcego czatu.
- `tools/verification_resolver_harness/run.sh`: **59/59 PASS**; `kotlinc -Werror` PASS.
- ZIP `unzip -t` i SHA-256 sprawdzone po utworzeniu.

## Ograniczenia
- Wybór hostowy jest bezpiecznym rozwiązaniem przejściowym: jeśli w jednym czacie jest więcej niż jeden zastosowany ChangeSet, dowody nie zostaną automatycznie dołączone. To celowe fail-closed; wymaga kolejnego etapu z jawnym `activeChangeSetId` zarządzanym przez UI/orkiestrator.
- Pełny Gradle/JUnit/Robolectric, Android SDK build/APK i test urządzeniowy: NOT RUN.
- `versionName=0.6.54`, `versionCode=80` bez zmian.
- Companion transport pozostaje nieaktywny.
