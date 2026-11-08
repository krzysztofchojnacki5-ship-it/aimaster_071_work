# Build status — 0.6.89-SOURCE

- Parent: `AI_MASTER_PRO_0_6_88_PERSISTENT_VERIFICATION_EVIDENCE_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: podłączyć zasady dowodowe do MASTER self-check i blokować niepoparte deklaracje o testach, kompilacji oraz buildzie Android.

## Zmiany

- Rozszerzono `AgentPrompts` dla MASTER-a: `COMPLETED` podagenta oznacza wyłącznie dostarczenie raportu; claim nie jest dowodem; bez ledgeru MASTER ma oznaczać weryfikację jako niepotwierdzoną/NOT_RUN.
- Zmieniono instrukcję syntezy w `MainActivity`: nie używać „zweryfikowana odpowiedź” jako domyślnej etykiety; jawnie wymagać kompletnego ledgeru PASS i odróżniać raport od dowodu.
- `MasterResponseCheck.evaluate()` przyjmuje opcjonalny `VerificationAssessment`.
- Kontrola rozróżnia trzy klasy twierdzeń:
  - „testy przeszły” wymagają PASS z UNIT_TESTS / INTEGRATION_TESTS / ROBOLECTRIC;
  - „kompilacja przeszła” wymaga PASS z COMPILATION;
  - „build PASS” / APK zbudowany wymaga PASS z ANDROID_BUILD.
- PASS z innej kategorii (np. integralność workspace albo kompilacja) nie autoryzuje twierdzenia o buildzie Android.
- CLAIM_ONLY, brak assessment, INCOMPLETE, NOT_RUN lub FAIL powodują ostrzeżenie self-checku zamiast pozornego potwierdzenia.

## Walidacja wykonana

- AgentPrompts: 4/4 PASS.
- MasterResponseCheck: 8/8 PASS, w tym claim bez dowodu, claim z prawidłowym dowodem, claim z niewłaściwą kategorią dowodu, rozdzielenie compile vs Android build.
- VerificationEvidence model: 6/6 PASS.
- ProjectTestRunner: 3/3 PASS.
- VerificationEvidenceStore: 8/8 PASS.
- Łącznie: 29/29 testów lokalnych w shim runnerach; zmienione źródła Kotlin kompilowane `kotlinc -Werror`.

## NOT RUN / ograniczenia

- Pełny Gradle/JUnit/Robolectric/`assembleDebug`/APK/urządzenie: NOT RUN.
- `MainActivity` nie został zbudowany w Android SDK; call site i treść promptu sprawdzono statycznie.
- Ledger jest zapisywany przez patch verification, ale nie jest jeszcze automatycznie dobierany do każdego czatu MASTER-a. Obecny self-check domyślnie nie otrzymuje assessment, więc konserwatywnie ostrzega przed twierdzeniami o wykonaniu testów/builda.
- Następny etap: jawne wiązanie task/chat/snapshot IDs i kontrolowane przekazywanie wyłącznie właściwych dowodów do MASTER-a; hostowe `executionId` musi pochodzić z realnego adaptera narzędzi.
- Companion transport pozostaje BLOCKED.
