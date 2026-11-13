# 0.6.85 — Companion concurrency / linearization audit

## Zakres

Audyt `promotePeerRotation()`, `revokePeer()` i `wipeAllTrust()` w stosunku do wspólnego monitora procesu, fail-closed latch, revocation fence, cleanup socketów i publishera zdarzeń.

## Wykrycie i poprawka

W 0.6.84 monitor i latch były współdzielone między instancjami `CompanionProvisioningStore`, lecz publisher revocation był polem instancji. Przy przypadkowym użyciu dwóch store w jednym procesie druga instancja mogła zmienić wspólny magazyn trust bez uruchomienia fence runtime zbindowanego do pierwszej instancji.

Publisher został przeniesiony do `ProcessTrustState`. Teraz monitor, latch i publisher mają ten sam zakres procesu. To usuwa rozjazd między zakresem serializacji mutacji i zakresem propagacji revocation.

## Audyt kolejności

- `promotePeerRotation()`: walidacja peer/staged pin przed fence; następnie fence, zapis promoted pin pod monitorem, cleanup i dispatch po zwolnieniu monitora.
- `revokePeer()`: fence przed odczytem/mutacją; nawet brak rekordu nie pomija eventu, aby ponowić unieważnienie ewentualnie pozostałych uchwytów.
- `wipeAllTrust()`: global fence przed commit; metadata alias jest usuwany dopiero po potwierdzonym commicie usunięcia pinów; process latch jest czyszczony dopiero po usunięciu i fsync markera plikowego.
- `CompanionTrustRevocationBinding`: begin fence tylko wykonuje synchroniczne odcięcie admission/detach; potencjalnie blokujące zamykanie socketów jest odroczone poza monitorem store.

## Testy

- Dodano `CompanionTrustMutationRaceTest`: peer revoke vs global wipe, dwa global wipe i konkurencyjne close batcha, błąd socket close bez przywrócenia registry ownership.
- Host JVM: 3/3 × 5 = 15/15 PASS; `kotlinc -Werror` PASS.
- Dodano Robolectric test integracyjny: mutation through second store fences runtime bound to first store. ADDED / NOT RUN.

## Ograniczenia

- Host race tests sprawdzają rzeczywisty registry/fence, nie trwałe operacje Android store.
- Gradle/JUnit/Robolectric/Android Keystore/device NOT RUN.
- Observer callback dispatch jest poza monitorem, więc kolejność zwykłych observerów przy konkurencyjnych operacjach nie jest gwarantowana. Aktualnie nie ma produkcyjnych rejestracji zwykłych observer listeners; session safety opiera się na fence.
- Companion transport pozostaje nieaktywny i BLOCKED.
