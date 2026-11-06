# CHANGELOG — 0.6.134: fail-closed cleanup interrupted temp evidence

- Naprawiono kolejność w `ProjectWorkspaceStore.cleanupInterruptedStaging()`: pełna weryfikacja `current/` względem manifestu odbywa się przed usuwaniem jakichkolwiek staging/temp/receipt/previous artifacts.
- Gdy current jest nieobecny, uszkodzony lub nie przechodzi integrity check, metoda zachowuje wszystkie artefakty mogące stanowić dowód przerwanej transakcji.
- Weryfikacja pozytywna uprawnia do cleanupu osieroconych `.staging-*`, `.patch-staging-*`, `.patch-recovery-*`, `.workspace.swap.*.tmp`, `.workspace.patch.receipt.*.tmp` i `.previous-*`.
- Symlink temp jest usuwany jako wpis; jego cel poza workspace pozostaje nietknięty.
- Dodano 2 hostowe scenariusze do rzeczywistego `ProjectWorkspaceStore` i 1 test JUnit (ADDED/NOT RUN). Workspace recovery: 15/15; pełna regresja: 206 hostowych PASS; compile shim/source guards PASS.
- Nie zmieniono AISW v1/v2, writer, commit point, directory fsync policy ani zakresu blokady importu.
- Android/Gradle/JUnit/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED). App `0.6.54 / code 80`; Companion Transport BLOCKED.
- Szczegóły: `WORKSPACE_TEMP_EVIDENCE_CLEANUP_0.6.134_PL.md` i `BUILD_STATUS_0.6.134_PL.md`.
