# 0.6.64-SOURCE — Companion revocation race hardening

- Naprawiono możliwość wywłaszczenia istniejącego uchwytu przez stale/replayed ticket innego peera.
- Dodano test regresyjny dla nieważnego ticketu i istniejącej własności.
- Dodano prunowanie epok peerów bez aktywnych ticketów/uchwytów; test 500 cykli.
- Dodano gwarantowany cleanup socketu, TLS receipt-u i registry release po błędzie przejścia stanu ownera.
- Dodano test awarii `closeForRevocation()`; pozostałe połączenia nadal są obsługiwane.
- Registry draft: 23/23 lokalny harness; kompilacja `kotlinc -Werror` registry/gate/core PASS.
- Brak builda Gradle, JUnit, APK i testu na urządzeniu.
