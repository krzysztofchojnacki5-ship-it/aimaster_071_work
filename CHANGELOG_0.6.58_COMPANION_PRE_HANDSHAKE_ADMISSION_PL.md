# 0.6.58-SOURCE — Companion pre-handshake admission

**Typ:** kontrakt i szkic adaptera pkt 4; bez wydania APK.  
**Baza:** `0.6.57-SOURCE`.  
**Aplikacja:** `versionName=0.6.54`, `versionCode=80` — bez zmian.

## Zmiany
- Dodano `drafts/pkt4/CompanionTlsHandshakeGate.kt`.
- Gate pobiera `CompanionConnectionRegistry.Ticket` przed wywołaniem `CompanionPinnedTlsProfile.accept()` lub `connect()`; te metody wykonują handshake TLS.
- Po zakończonym handshake gate rejestruje socket z tym samym biletem. Jeśli w trakcie handshake nastąpiło unieważnienie, rejestr odrzuca stary bilet i zamyka socket.
- Po odrzuceniu gate usuwa także nieodebrany receipt z profilu TLS, aby nie pozostawić zajętego slotu.
- `CompanionPinnedTlsProfile.discard(receipt)` usuwa nieodebrany dowód TLS; jest idempotentne i nie ujawnia metadanych.
- Registry przechowuje `OwnedConnection`, nie surowy socket; revocation zamyka wrapper, który idempotentnie zamyka socket, usuwa receipt i zwalnia własność.
- Dodano test regresyjny do szkicu rejestru: ticket pobrany przed handshake staje się nieaktualny po revocation, a późny socket jest zamykany.

## Walidacja wykonana
- `kotlinc -Werror` dla `CompanionConnectionRegistry.kt` + `CompanionTlsHandshakeGate.kt` z lokalnymi atrapami typów TLS: PASS.
- `kotlinc -Werror` dla rzeczywistego `CompanionPinnedTlsProfile.kt` + gate + registry z atrapami wyłącznie zależności platformowych TLS/SPKI: PASS.
- Lokalny harness rejestru: PASS — stary ticket odrzucony i socket zamknięty; aktualny ticket przyjęty, a następnie zamknięty przez revocation.
- 14/14 metod testowych szkicu uruchomiono przez lokalny shim JUnit-compatible: PASS; to nadal NIE jest uruchomienie JUnit.
- Nowy test JUnit szkicu: NOT RUN.
- Pełne Gradle / `testDebugUnitTest` / `assembleDebug`: NOT RUN.
- Android Keystore, rzeczywisty handshake na urządzeniu i testy integracyjne: NOT RUN.

## Ograniczenia
- Gate pozostaje w `drafts/pkt4`, poza Android source set; nie jest używany przez aplikację.
- Integracja z `CompanionSessionOwner` musi jeszcze wymusić zgodność peer ID z TLS identity przed przejściem do ACTIVE i ustawić ownera w terminalnym `FAILED` po revocation.
- Każdy caller musi zamykać zasób przez `OwnedConnection.close()`; surowe zamknięcie `socket` poza wrapperem pozostawia uchwyt w registry do czasu revocation. Docelowy adapter powinien ograniczyć ekspozycję surowego socketu.
- R2/R3/R4/R6 pozostają otwarte. Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
