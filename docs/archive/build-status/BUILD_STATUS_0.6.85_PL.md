# Build status — 0.6.85-SOURCE

- Parent: `AI_MASTER_PRO_0_6_84_COMPANION_IO_ADAPTERS_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: audyt współbieżności promote/revoke/wipe oraz wspólnego procesu unieważniania sesji.

## Zmiana produkcyjna

- Audyt potwierdził, że `promotePeerRotation()`, `revokePeer()` i `wipeAllTrust()` wykonują pre-commit fence oraz zmianę trwałego trust pod tym samym `PROCESS_TRUST_STATE.monitor`.
- `CompanionTrustRevocationBinding` rozpoczyna wyłącznie synchroniczne odcięcie admission i detach uchwytów. Cleanup socketów (`FenceBatch.close`) i dispatch obserwatorów następują po zwolnieniu monitora store.
- Wykryto niespójność zakresu: monitor/latch były współdzielone przez wszystkie instancje store, ale `CompanionTrustRevocationPublisher` był lokalny dla instancji. Mutacja wykonana przez inną instancję mogła więc nie uruchomić fence runtime przypiętego do pierwszej instancji.
- Naprawa: publisher przeniesiono do `ProcessTrustState`; każda instancja `CompanionProvisioningStore` w tym samym procesie używa wspólnego publishera, zgodnie ze wspólnym monitorem i latch.
- Dodano test Robolectric `mutationThroughSecondStoreFencesRuntimeBoundToFirstStore()` — ADDED / NOT RUN. Test sprawdza, że wipe przez drugą instancję zamyka uchwyt registry runtime zbindowanego do pierwszej.

## Walidacja wykonana

- Rzeczywisty `CompanionConnectionRegistry.kt` + nowy test `CompanionTrustMutationRaceTest.kt`: `kotlinc -Werror` PASS.
- Lokalny JVM shim runner: 3/3 testy PASS na uruchomienie × 5 = 15/15 PASS. Testy obejmują peer revoke vs global wipe, dwa równoległe wipe/fence close oraz błąd zamknięcia socketu bez odtworzenia ownership.
- To są testy hostowe klasy registry i nie stanowią uruchomienia Android store, JUnit ani Robolectric.
- Statyczny audyt kodu store i lifecycle bindingu: PASS; wszystkie trzy destrukcyjne operacje wchodzą w ten sam monitor procesu przed rozpoczęciem fence.
- Gradle/JUnit/Robolectric NOT RUN; Android Keystore/device NOT RUN; `assembleDebug`/APK NOT RUN.
- Archiwum źródłowe zweryfikować przez `unzip -t` oraz SHA-256 po spakowaniu.

## Granice i ryzyka pozostające

- Synchronizacja pozostaje process-local; brak gwarancji multi-process.
- Post-commit observer callbacks są wywoływane poza monitorem. Ich kolejność pomiędzy równoległymi operacjami nie jest gwarantowana. W aktualnym production source nie znaleziono rejestracji zwykłych observer listeners; krytyczne odcięcie sesji realizuje pre-commit fence pod monitorem.
- Test integracyjny współdzielenia publishera wymaga uruchomienia Robolectric/Android Keystore na środowisku właściciela.
- Transport Companion nadal BLOCKED / nie uruchamiać.
