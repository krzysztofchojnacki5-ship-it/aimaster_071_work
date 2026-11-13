# AI MASTER PRO 0.6.96-SOURCE — jawny aktywny ChangeSet hosta

- Baza 0.6.95-SOURCE; wersja aplikacji pozostaje 0.6.54 / versionCode 80.
- Dodano `HostActiveChangeSetStore` z trwałym tuple: chatId, workspace manifest SHA-256, changeSetId, taskId, snapshotId, selectedAt.
- MASTER nie wybiera już spośród wszystkich zastosowanych ChangeSet. Korzysta wyłącznie z dokładnego identyfikatora zapisanego przez hosta.
- Wskaźnik powstaje po apply + snapshot + `APPLIED_UNVERIFIED`; odczyt rewaliduje rekord w ChangeSetStore. Brak aktywnego wskaźnika oznacza NONE; niezgodność, uszkodzenie, brak wpisu, duplikat, status końcowy lub inny czat oznacza INVALID.
- Workspace identity jest SHA-256 bieżącego `current.manifest`, a nie liczbą plików/rozmiarem/nazwą projektu. `ApplyResult` przenosi digest manifestu utworzonego przez patch; aktywacja sprawdza, czy bieżący manifest nadal odpowiada temu digestowi. `currentIdentity()` jest read-only i nie czyści stagingu równoległego importu.
- Rollback i odrzucenie czyszczą aktywny wskaźnik. Zmiana czatu najpierw czyści wskaźnik (niepowodzenie blokuje przełączenie); import workspace czyści go po sukcesie, a odczyt obcego czatu/workspace próbuje trwale usunąć stary wpis. Aktywacja patcha jest serializowana z przełączeniem czatu.
- `ChangeSetStore` zapisuje synchronicznie przez `commit()` i zgłasza błąd, jeśli zapis nie został potwierdzony.
- W audycie wykryto i usunięto ryzyko lock inversion między rejestrem zmian a store aktywnego scope.
- Testy lokalnego harnessu: 65/65 PASS. Globalna serializacja wszystkich instancji `ProjectWorkspaceStore` między importem i apply pozostaje osobnym zadaniem audytu. Robolectric testy store oraz manifest identity dodane, NOT RUN. Gradle/Android build/APK/device NOT RUN.
- Companion transport nadal BLOCKED.
