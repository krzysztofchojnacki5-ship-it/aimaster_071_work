# BUILD STATUS — AI MASTER PRO 0.6.95-SOURCE

- Baza: `AI_MASTER_PRO_0_6_94_MASTER_EVIDENCE_SYNTHESIS_INTEGRATION_SOURCE.zip`.
- Zweryfikowano bazowy ZIP: 864,974 B; SHA-256 `ea03d46a0896791d85ecb755e4329f00b483ef7c60052b0f9f36ed2b55238fa2`; `unzip -t` PASS.
- Dodano hostowy wybór zakresu dowodów: tylko jeden kwalifikujący się ChangeSet z aktywnego czatu, status `APPLIED_UNVERIFIED`/`APPLIED`, z snapshotId.
- Zero kandydatów = NONE; wielu = AMBIGUOUS; malformed scope = INVALID. Nie ma fallbacku do najnowszego wpisu.
- Dodano `MasterEvidenceContextBuilder.buildForHostScope()` i podłączono go do obu call sites MASTER w `MainActivity`.
- Prompt użytkownika nie musi już zawierać changeSetId/taskId/snapshotId, aby host przekazał dowody.
- Testy host scope i buildera obejmują selekcję pojedynczą, ambiguity, chat isolation, brak snapshotu, malformed candidate i exact-scope validation.
- Lokalny harness `tools/verification_resolver_harness/run.sh`: **59/59 PASS**; `kotlinc -Werror` PASS.

## Ograniczenia / NOT RUN
- W jednym czacie z wieloma zastosowanymi ChangeSet wybór jest celowo AMBIGUOUS. Nie używamy czasu aktualizacji jako arbitra. Następny etap powinien dodać jawny `activeChangeSetId` w stanie hosta/orkiestratora.
- Pełny Gradle/JUnit/Robolectric: NOT RUN.
- Android SDK build / assembleDebug / APK / urządzenie: NOT RUN.
- `versionName=0.6.54`, `versionCode=80` bez zmian.
- Companion transport pozostaje nieaktywny.
