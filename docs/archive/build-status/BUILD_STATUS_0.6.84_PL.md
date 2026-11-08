# Build status — 0.6.84-SOURCE

- Parent: `AI_MASTER_PRO_0_6_83_COMPANION_FAULT_INJECTION_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Cel: zastąpić wyłącznie punkty fault injection adapterem I/O, który pozwala testować rzeczywiste wyniki operacji produkcyjnego koordynatora Companion.

## Zmiany produkcyjne

- Wprowadzono wewnętrzny kontrakt `CompanionTrustIo` oraz domyślną implementację `AndroidCompanionTrustIo`.
- Adapter obejmuje `SharedPreferences.Editor.commit()`, atomowy replace pliku markera, `deleteIfExists`, fsync katalogu i usuwanie aliasu Android Keystore.
- `CompanionProvisioningStore` przyjmuje adapter przez konstruktor wewnętrzny; standardowy graph aplikacji nadal używa rzeczywistego adaptera Android.
- Wprowadzono współdzielony dla całego procesu aplikacji monitor i latch fail-closed. Mutacje provision/stage/cancel/promote/revoke/wipe oraz odczyt zaszyfrowanego magazynu są serializowane pomiędzy instancjami store w obrębie jednego procesu.
- Nie deklarujemy synchronizacji międzyprocesowej. Monitor i latch są statycznym stanem procesu aplikacji; utrzymują fail-closed także wtedy, gdy tworzona jest kolejna instancja store.

## Testy dodane

- `falsePreferencesCommitUsesProductionLockRecoveryPath()` — adapter zwraca `false` przy pierwszym commicie; test sprawdza późniejsze markery i współdzielenie process latch przez nową instancję.
- `keystoreAliasDeletionFailureIsSurfacedAfterCommittedWipe()` — adapter rzuca błąd podczas usuwania aliasu po zatwierdzonym wipe; oczekiwany jest jawny `CompanionTrustRevocationException`.
- `destructiveMutationsSerializeAcrossStoreInstancesInOneProcess()` — blokowany commit sprawdza, że dwie instancje używają wspólnego monitora.
- Istniejące testy granic publikacji/usuwania markera pozostają.
- Wszystkie testy Robolectric są ADDED / NOT RUN; nie raportujemy ich jako PASS.

## Walidacja wykonana

- Kontrola statyczna: brak pozostałych `synchronized(this)` w `CompanionProvisioningStore`; wszystkie ścieżki trust read-modify-write używają współdzielonego monitora procesu.
- `kotlinc -Werror`: produkcyjny `CompanionProvisioningStore.kt` oraz źródło testów `CompanionTrustPersistentLockTest.kt` skompilowane z wąskimi atrapami Android/JSON/core/JUnit/Robolectric API: PASS. To kontrola składni i zgodności typów z atrapami, nie kompilacja Android SDK ani uruchomienie Robolectric.
- Hostowy harness uruchomił rzeczywisty kod `CompanionProvisioningStore` z atrapami Android Context/SharedPreferences i adapterami: 3/3 PASS — `commit() == false` pozostawia wspólny latch fail-closed, błąd alias deletion jest raportowany po commicie, a dwie instancje serializują destrukcyjne mutacje. Nie jest to test Android framework/Keystore.
- Kontrola równowagi nawiasów źródeł Kotlin: PASS.
- Istniejący hostowy NIO harness z 0.6.83 nadal daje 200/200 PASS, a directory-fsync harness 100/100 PASS; oba są testami hostowego protokołu plikowego i nie wykonują adapterów Android ani nowej logiki monitorowania.
- Gradle/JUnit/Robolectric NOT RUN; Android Keystore NOT RUN; `assembleDebug`/APK/device NOT RUN.

## Ograniczenia i otwarte ryzyka

- Wspólny monitor obejmuje wyłącznie instancje w jednym procesie Android. Nie ma blokady międzyprocesowej ani deklaracji multi-process support.
- Testy adaptera są zapisane, ale wymagają uruchomienia Robolectric/Gradle; zachowanie `FileChannel.force(true)` na katalogu wymaga urządzenia/OEM filesystemu.
- Awaria jednocześnie obu trwałych markerów oraz usunięcia aliasu Keystore nadal nie gwarantuje fail-closed po restarcie.
- Transport Companion pozostaje nieaktywny / BLOCKED.

## Następny etap

Uruchomić pełne testy Robolectric na środowisku z dostępnym Gradle, dopracować testy współbieżności promote/revoke/wipe z różnymi instancjami i sprawdzić kolejność revocation fence/listenerów przy równoległych mutacjach. Nie aktywować transportu przed zamknięciem tych testów.
