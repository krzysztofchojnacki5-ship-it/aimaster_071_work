# CHANGELOG — AI MASTER PRO 0.6.133

- Audyt rzeczywistego swap recovery ujawnił, że po poprawnym zatwierdzeniu `current/` mogły pozostać osierocone `.previous-*` artefakty z wcześniejszych transakcji.
- `cleanupInterruptedStaging()` usuwa teraz osierocone previous directories/manifests wyłącznie po pełnej weryfikacji `current/` względem `current.manifest`.
- Symlinki previous artifacts są odpinane jako wpisy, bez podążania do celów; przy nieważnym/niekompletnym current backupy pozostają nietknięte.
- Rozszerzono rzeczywisty hostowy `workspace_transaction_recovery_harness` z 12 do 13 scenariuszy; kompiluje i wykonuje produkcyjny `ProjectWorkspaceStore.kt`; 13/13 PASS.
- Dodano analogiczny JUnit test do `ProjectWorkspaceStoreTest.kt` (ADDED / NOT RUN — brak Gradle/JUnit runtime).
- Pełna regresja hostowa: 204/204 PASS; compile-only shim `-Werror` PASS; source guards PASS. Harnessy uruchomiono indywidualnie.
- Nie zmieniono formatu AISW v1/v2 ani punktu commit. Produkcyjny import ZIP nadal działa pod global workspace lock; staged import nie jest zintegrowany.
- Android/Gradle/JUnit/Robolectric/APK/device/process-death NOT RUN; preflight BLOCKED. App `0.6.54 / code 80`; Companion Transport BLOCKED.
