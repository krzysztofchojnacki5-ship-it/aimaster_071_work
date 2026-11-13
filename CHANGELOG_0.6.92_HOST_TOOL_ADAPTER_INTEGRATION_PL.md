# CHANGELOG 0.6.92 — rzeczywisty host adapter i rejestr wykonania

## Baza
- `AI_MASTER_PRO_0_6_91_HOST_TOOL_EXECUTION_REGISTRY_SOURCE.zip`
- Aplikacja pozostaje `versionName=0.6.54`, `versionCode=80`.

## Zmiany
- Dodano `EvidenceTrackedToolAdapter`, który opakowuje rzeczywistą operację hosta w lifecycle `begin → execute → complete/abandon`.
- Przed uruchomieniem operacji rejestr musi trwale zapisać `NOT_RUN`; błąd tego zapisu blokuje wykonanie.
- Zwykły powrót z operacji zapisuje `TOOL_EXECUTION/PASS`, ale opis jawnie ogranicza PASS do faktu, że adapter zwrócił wynik. Nie jest to PASS testów projektu.
- Wyjątek operacji zapisuje `TOOL_EXECUTION/FAIL`; anulowanie lub przerwanie wątkiem zapisuje `NOT_RUN`.
- Błąd zapisu zakończenia nie tworzy sukcesu; pozostaje wcześniej utrwalony `NOT_RUN`.
- Podłączono adapter do ręcznej akcji weryfikacji patcha w `MainActivity` (`project.workspace.verify`). Wyniki domenowe `ProjectTestRunner` nadal zapisują osobne typowane dowody, a `PROJECT_TESTS` pozostaje `NOT_RUN`.
- Historyczne ChangeSet bez `chatId` są obsługiwane bez generowania sztucznego identyfikatora: weryfikacja może się wykonać, ale jej wykonanie nie jest przypinane do exact-scope registry.

## Walidacja
- `kotlinc -Werror` dla `VerificationEvidence`, `VerificationEvidenceStore`, `ToolExecutionRegistry`, `EvidenceTrackedToolAdapter`, `MasterResponseCheck` oraz pięciu klas testowych: PASS.
- Lokalny shim-JUnit: 38/38 PASS (registry, adapter, evidence store, evidence model, MASTER response check).
- Testy adaptera pokrywają: brak startu przy błędzie zapisu NOT_RUN, host-generated executionId, wyjątek, anulowanie oraz awarię zapisu completion.
- `MainActivity` call site: kontrola statyczna zmian; pełna kompilacja Android NOT RUN.
- Gradle/JUnit/Robolectric/Android build/APK/urządzenie: NOT RUN.

## Granice
- To pierwszy podłączony adapter, nie globalny rejestr wszystkich operacji aplikacji.
- Nie podłączono jeszcze wywołań `ProviderClient`, TeamRunEngine, import/export ani przyszłych executorów.
- Nie uruchamiamy kodu importowanego projektu; `PROJECT_TESTS` pozostaje NOT_RUN bez izolowanego executora.
- Companion transport pozostaje BLOCKED.

## Następny etap
- 0.6.93: exact-scope `VerificationEvidenceResolver` dla bieżącego `taskId/chatId/snapshotId`, walidacja spójności zakresu i filtrowanie kategorii przed przekazaniem do MASTER synthesis.
