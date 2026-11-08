# Build status — 0.6.91-SOURCE

- Parent: `AI_MASTER_PRO_0_6_90_EVIDENCE_SCOPE_BINDING_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: dodać host-owned lifecycle rzeczywistych wywołań narzędzi i uniemożliwić utworzenie wiarygodnego TOOL_EXECUTION PASS na podstawie samego tekstu agenta.

## Zmiany

- Dodano `ToolExecutionRegistry` z host-generated `executionId` i nieprzezroczystym uchwytem powiązanym z konkretną instancją registry.
- `begin()` zapisuje trwały rekord NOT_RUN przed zwróceniem uchwytu. Jeśli ledger nie zapisze rozpoczęcia, uchwyt nie zostaje wydany i adapter nie powinien uruchomić narzędzia.
- `complete()` przyjmuje wyłącznie PASS/FAIL, sprawdza tożsamość uchwytu i dopuszcza dokładnie jedno zakończenie.
- `abandon()` zapisuje NOT_RUN; nie może utworzyć PASS.
- Wynik completion jest zwracany jako dowód wyłącznie po poprawnym zapisie w sinku. Przy awarii zapisu pozostaje wcześniejszy NOT_RUN.
- Wyścig complete-vs-abandon jest serializowany; tylko jedna ścieżka może zużyć uchwyt.
- Dowody są wiązane z taskId/chatId/snapshotId z 0.6.90.

## Walidacja wykonana

- `VerificationEvidence` + `VerificationEvidenceStore` + `ToolExecutionRegistry` + testy: `kotlinc -Werror`, lokalny shim runner **25/25 PASS**.
- `ProjectTestRunner` + workspace/patch/manifest/scanner i testy z 0.6.90: `kotlinc -Werror`, lokalny shim runner **3/3 PASS**.
- Łącznie uruchomione testy lokalne: **28/28 PASS**.
- `ChangeSetStore` + `ChangeSafetyGuard` w 0.6.90 skompilowane z atrapami Android Context/SharedPreferences i JSON.
- ZIP source archive: test integralności `unzip -t` po utworzeniu.

## NOT RUN / ograniczenia

- Pełny Gradle/JUnit/Robolectric/`assembleDebug`/APK/urządzenie: NOT RUN.
- `MainActivity` nie został zbudowany przez Android SDK.
- Registry jest gotowym hostowym prymitywem, ale nie ma jeszcze adapterów narzędzi podłączonych do `begin()/complete()`. Nie wolno twierdzić, że konkretne narzędzia aplikacji są już rejestrowane.
- Obecne `ProjectTestRunner` wykonuje wyłącznie kontrolę integralności i nadal zgłasza PROJECT_TESTS=NOT_RUN; nie jest executor-em kompilacji/testów importowanego kodu.
- MASTER nie pobiera jeszcze automatycznie evidence z ledgeru. Exact-scope lookup z 0.6.90 istnieje, ale resolver i przekazanie assessment do syntezy pozostają otwarte.
- Companion transport BLOCKED; pkt 5 nadal OPEN/PARTIAL do czasu Gradle/Robolectric/Android Keystore/device.

## Następny etap

1. Wskazać rzeczywiste granice hostowych operacji w aplikacji i podłączyć adaptery begin/complete.
2. Zapewnić, że wynik provider/network call nie jest mylony z testem projektu ani Android build.
3. Dodać `VerificationEvidenceResolver` wybierający wyłącznie jawnie wskazany task/chat/snapshot.
4. Przekazać wynik assessment do MASTER synthesis i `MasterResponseCheck`; brak scope/evidence ma skutkować NOT_RUN/INCOMPLETE.
