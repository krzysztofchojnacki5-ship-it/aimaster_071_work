# 0.6.65-SOURCE — Companion revocation binding lifecycle hardening

## Zmiany
- Dodano `CompanionTrustRevocationSource`, minimalny kontrakt add/remove listenerów; `CompanionProvisioningStore` go implementuje.
- `CompanionTrustRevocationBinding` nie zależy już bezpośrednio od konkretnego Android store, co pozwala testować cykl życia bez Android Keystore.
- Naprawiono wyścig `publish()` kontra `close()`: binding ma własną blokadę lifecycle i stan `closed`. `close()` czeka na zakończenie callbacku już trwającego; callback ze snapshotu publishera, który dotrze po zamknięciu, nie wykona `registry.revoke()`.
- Dodano dwa testy: zamknięcie czeka na callback revocation w toku oraz po powrocie `close()` kolejne zdarzenie nie unieważnia aktywnego handle.

## Walidacja wykonana
- `kotlinc -Werror` dla publishera, registry, bindingu i testów: PASS.
- Lokalny JVM harness dla testów binding lifecycle: 2/2 PASS.
- To nie jest uruchomienie JUnit/Gradle. Pełny build Android, testy Android Keystore i test na urządzeniu: NOT RUN.

## Status
- Binding nadal jest szkicem w `drafts/pkt4`; nie został podłączony do lifecycle aplikacji.
- `versionName=0.6.54`, `versionCode=80` pozostają bez zmian; nie utworzono APK.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
