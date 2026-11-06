# AI MASTER PRO 0.6.97-SOURCE — fail-closed ChangeSet ledger

- Baza 0.6.96-SOURCE; aplikacja nadal 0.6.54 / versionCode 80.
- Dodano `ChangeSetStore.readResult()` z rozróżnieniem VALID/CORRUPT; poprawnie pusty ledger pozostaje stanem VALID.
- Parser odrzuca nieparsowalny lub nadmiernie duży JSON, wadliwe wiersze, puste/duplikowane ID, nieznane statusy, niepoprawne `testLevel`/`testStatus` i błędne typy tablic.
- Mutacje korzystają z `readOrThrow()` i nie nadpisują uszkodzonego ledgeru pustą listą.
- Host active scope jest INVALID przy korupcji ChangeSet ledger; MASTER nie otrzyma dowodów. Active pointer wiąże również bieżący status ChangeSet, a resolver candidate jest tworzony z jednego zweryfikowanego odczytu hosta.
- UI diagnostyczne pokazuje CORRUPT i blokadę zapisu; nie myli korupcji z pustym rejestrem.
- Błędy parsera są sanitizowane; nie wyświetlamy surowych fragmentów JSON.
- Resolver harness 65/65 PASS. Osobny `changeset_store_compile_harness` kompiluje `ChangeSetStore` i `HostActiveChangeSetStore` przez `kotlinc -Werror` z compile-only shimami Android/JSON API. Nowe testy Robolectric ADDED/NOT RUN; Android/Gradle/APK/device NOT RUN.
