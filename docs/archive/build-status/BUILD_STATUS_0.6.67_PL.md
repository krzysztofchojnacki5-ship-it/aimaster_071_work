# BUILD STATUS — 0.6.67-SOURCE

**Typ:** utwardzenie ścieżek błędów revocation w Companion; paczka źródłowa, bez APK.  
**Baza:** 0.6.66-SOURCE.  
**Wersja aplikacji:** `0.6.54` / `versionCode=80` (bez zmian).

## Wykryte problemy i poprawki

1. `wipeAllTrust()` przechwytywało tylko `Exception` podczas usuwania aliasu Keystore. Fatalny `Error` po zatwierdzonym usunięciu pinów mógł przerwać metodę przed globalnym dispatch revocation. Teraz każdy `Throwable` z etapu alias cleanup jest zachowany, globalne unieważnienie jest mimo niego rozsyłane, a fatalny błąd jest propagowany dopiero po próbie obsłużenia wszystkich listenerów.
2. `wipeAllTrust()` i `revokePeer()` mogły nie uruchomić revocation, gdy zapis magazynu zwrócił błąd albo wynik był niejednoznaczny. Obie operacje teraz wykonują dispatch także po nieudanej próbie zmiany zaufania. W takim przypadku sesje są zamykane fail-closed, a metoda raportuje, że trwałej zmiany magazynu nie udało się potwierdzić.
3. `CompanionSessionOwner.isPeerTrusted()` wcześniej nie przechwytywał fatalnego `Error`. Podczas późniejszej operacji sesji taki błąd mógł wydostać się na zewnątrz, pozostawiając ownera w stanie `ACTIVE`/`TRANSFERRING`. Teraz owner najpierw czyści odbiornik i tożsamość oraz przechodzi do `FAILED`, a następnie ponownie rzuca fatalny błąd.

## Walidacja lokalna

- `kotlinc -Werror` dla wszystkich klas `Companion*.kt` core oraz `CompanionProvisioningStore.kt` z lokalnymi atrapami Android/JSON: PASS. Atrapy służą do sprawdzenia kompilacji, nie symulują Android Keystore ani zachowania SharedPreferences.
- `kotlinc -Werror` dla core + draft registry/gate/binding + testów registry/publisher/binding: PASS.
- Registry 25/25, publisher 3/3, binding lifecycle 2/2: **30/30 PASS** w lokalnym JVM harnessie z shimem JUnit.
- Owner/TLS/loopback gate: **25/25 PASS**, w tym nowy test fatalnego błędu walidatora podczas `beginTransfer()`.
- Łącznie: **55/55 PASS** lokalnego harnessu. To nie jest uruchomienie JUnit/Gradle ani test Android.

## NOT RUN

- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- Robolectric / prawdziwy Android Keystore / test na urządzeniu
- Test integracyjny store→binding→registry z rzeczywistymi SharedPreferences i Keystore

## Status architektury

- `CompanionConnectionRegistry`, `CompanionTlsHandshakeGate` i `CompanionTrustRevocationBinding` nadal pozostają w `drafts/pkt4`, poza aktywnym source set.
- Nie uznaję atomowego spięcia store→registry za zakończone. Nadal wymaga testów kolejności i współbieżności na granicy commit/dispatch/admission.
- `versionName=0.6.54`, `versionCode=80`; APK nie został utworzony.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.

## Następny etap

Zaprojektować i przetestować dwufazowy fence revocation, aby epoka admission była unieważniana w tej samej sekcji krytycznej co trwała zmiana pinów, natomiast zamykanie socketów nadal odbywało się poza monitorem store. Nie przenosić szkicu do produkcji bez testu tej kolejności.
