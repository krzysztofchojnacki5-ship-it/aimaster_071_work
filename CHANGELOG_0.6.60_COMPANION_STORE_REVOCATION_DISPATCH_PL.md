# 0.6.60-SOURCE — Companion store revocation dispatch

**Typ:** produkcyjny publisher zdarzeń trust + szkic bindingu store/registry; bez APK.  
**Baza:** `0.6.59-SOURCE`.  
**Aplikacja:** `versionName=0.6.54`, `versionCode=80` — bez zmian.

## Zmiany
- Dodano `CompanionTrustRevocationEvent`, listener i `CompanionTrustRevocationPublisher` oparty na `CopyOnWriteArrayList`. Publisher próbuje wszystkich listenerów i zwraca zebrane wyjątki; nie wywołuje callbacków pod własną blokadą.
- `CompanionProvisioningStore.revokePeer()` nie jest już metodą `@Synchronized`: trwały commit odbywa się w `synchronized(this)`, a zdarzenie peer-revocation jest publikowane dopiero po zwolnieniu monitora. Ponowienie dla nieobecnego peer ID także publikuje zdarzenie. Brak commit oznacza brak eventu.
- `wipeAllTrust()` zatwierdza usunięcie pinów i próbuje usunąć alias Keystore w jednej sekcji store. Callbacki są wywoływane po wyjściu z monitora. Błąd aliasu nie zatrzymuje publikacji globalnego revocation.
- Dodano `CompanionTrustRevocationException` z `trustChangeCommitted=true`; wyjątek jest zgłaszany po próbie wszystkich listenerów i po próbie usunięcia aliasu.
- Dodano draft `CompanionTrustRevocationBinding`, który łączy zdarzenia store z `CompanionConnectionRegistry.revoke()`. Nadal poza source set.
- Dodano testy publishera: wszystkie callbacki są próbowane mimo błędu wcześniejszego listenera; global event i remove listener działają poprawnie.

## Walidacja wykonana
- Kotlin store/core/gate/binding z atrapami Android/JSON, `kotlinc -Werror`: PASS (kontrola składni i typów, nie test platformy).
- Publisher JVM harness: 2/2 PASS (nie JUnit/Gradle).
- Poprzednie kontrole 0.6.59: owner 19/19, registry 16/16 przez shimy/harnessy JVM.
- Android Keystore, Robolectric, Gradle, APK i testy urządzenia: NOT RUN.

## Ograniczenia
- Binding store → registry jest szkicem w `drafts/pkt4`; aplikacja nie używa go jeszcze.
- R3/R4 rozwiązane na poziomie API store/publisher i kontraktu, ale pkt 4 nie jest zamknięty bez integracji registry/owner i testów platformowych.
- R6 nadal wymaga pełnego audytu receipt lifecycle, w tym expiry/capacity i cleanup wszystkich ścieżek błędów.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
