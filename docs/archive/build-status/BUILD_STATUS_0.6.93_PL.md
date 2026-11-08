# BUILD STATUS — AI MASTER PRO 0.6.93-SOURCE

- Baza: `AI_MASTER_PRO_0_6_92_HOST_TOOL_ADAPTER_INTEGRATION_SOURCE.zip`.
- Wersja aplikacji bez zmian: `versionName=0.6.54`, `versionCode=80`.
- Dodano `VerificationEvidenceResolver`, który pobiera dowody wyłącznie przez exact scope `taskId + chatId + snapshotId`.
- Resolver odrzuca deklaracje `AGENT_CLAIM` z oceny weryfikacyjnej; pozostawia je osobno jako kontekst, bez możliwości spełnienia wymaganych kontroli.
- Wynik `TOOL_EXECUTION` jest akceptowany tylko wtedy, gdy istnieje pasujący hostowy marker `NOT_RUN` o tym samym `executionId`, rodzaju dowodu i zakresie, a czas wyniku jest późniejszy niż czas rozpoczęcia.
- Nowszy `NOT_RUN` (np. rozpoczęta, ale niedokończona operacja) blokuje wykorzystanie wcześniejszego PASS dla tego rodzaju dowodu. Nowszy FAIL ma pierwszeństwo.
- Uszkodzony ledger, puste ID zakresu lub pusty zestaw wymaganych kontroli zwracają jawny stan błędu; resolver nie rozszerza zakresu ani nie zgaduje identyfikatorów.
- `ToolExecutionRegistry.begin()` i `abandon()` zapisują teraz `executionId` także w markerach NOT_RUN, aby można było sprawdzić cykl życia wywołania.
- Dodano 10 testów resolvera obejmujących izolację zakresu, claim-only, orphan execution, zgodność kind/ID/czasu, FAIL, NOT_RUN, uszkodzony ledger i brakujące kontrole.

## Walidacja wykonana
- `kotlinc -Werror` dla modeli evidence, store, registry, adaptera, resolvera, `MasterResponseCheck` oraz odpowiadających im testów: PASS.
- Lokalny hostowy shim-JUnit: **48/48 PASS** (Resolver, Registry, Store, Evidence, Adapter, MasterResponseCheck).
- Archiwum źródłowe: `unzip -t` PASS.

## NOT RUN / ograniczenia
- Pełny Gradle/JUnit/Robolectric: NOT RUN.
- Android SDK build / `assembleDebug` / APK: NOT RUN.
- Test urządzeniowy i Android Keystore: NOT RUN.
- Resolver jest gotowy jako typowana warstwa core, ale nie został jeszcze podłączony do właściwego promptu/syntezy MASTER-a. To kolejny etap.
- Lokalny ledger nie jest kryptograficznym dowodem przeciwko zmodyfikowanemu procesowi aplikacji lub ręcznie zmienionym danym urządzenia; jest to fail-closed kontrola spójności i pochodzenia w ramach zaufanego hosta aplikacji.
- Companion transport pozostaje nieaktywny.
