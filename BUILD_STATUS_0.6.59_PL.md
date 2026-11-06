# BUILD STATUS — 0.6.59-SOURCE — Companion owner revocation signal

**Typ:** terminalny sygnał revocation + szkic adaptera registry/owner; bez APK.  
**Baza:** `0.6.58-SOURCE`.  
**Aplikacja:** `versionName=0.6.54`, `versionCode=80` — bez zmian.

## Zmiany
- Dodano `CompanionSessionOwner.invalidateForPeerRevocation()` jako wewnętrzne, synchronizowane przejście do `FAILED`. Metoda jest idempotentna, nie zamyka socketu, ale zamyka receiver, czyści identyfikację TLS/parowania i zeruje sekret sesji.
- Dodano `CompanionRevocationAwareConnection`: registry rozróżnia zwykłe zamknięcie od unieważnienia peer. Callback jest wykonywany po zdjęciu locka registry.
- `OwnedConnection` implementuje callback revocation. Najpierw ustawia ownera w `FAILED`, następnie zamyka socket; cleanup receipt i `release()` odbywają się w `finally`.
- Gate aktywuje ownera z receipt-em TLS przed rejestracją handle. Wiąże ticket.peerId z rzeczywistą tożsamością przez `owner.activate(receipt)`. Jeżeli revocation wystąpiło w czasie handshake, rejestracja starego ticketu wywołuje `closeForRevocation()` i owner przechodzi do `FAILED`.
- Dodano dwa testy registry: callback revocation dla aktywnego uchwytu i callback dla starego ticketu. Dodano test ownera potwierdzający terminalny stan i wyczyszczenie tożsamości.

## Walidacja wykonana
- Kotlin core + rzeczywisty `CompanionPinnedTlsProfile` + registry/gate, `kotlinc -Werror`: PASS.
- `CompanionSessionOwnerTest`: 19/19 PASS przez lokalny JVM reflection harness z adnotacjami JUnit-compatible i rzeczywistymi certyfikatami testowymi; NIE jest to uruchomienie JUnit/Gradle.
- Testy szkicu registry: 16/16 PASS przez lokalny shim asercji; NIE jest to JUnit.
- Android Studio / Gradle / APK / Android Keystore / urządzenie: NOT RUN.

## Ograniczenia
- Registry i gate nadal są w `drafts/pkt4`, poza source set. Store nie publikuje jeszcze zdarzeń revocation; zatem aplikacja nie korzysta z tego mechanizmu.
- `CompanionProvisioningStore` wymaga R3/R4: commit trust change i publikacja callbacku poza monitorem; `wipeAllTrust()` musi unieważnić sesje po udanym usunięciu pinów nawet przy błędzie usuwania aliasu Keystore.
- R6 (pełny cykl życia receiptów, w tym timeout i cleanup po błędach) wymaga osobnej kontroli.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
