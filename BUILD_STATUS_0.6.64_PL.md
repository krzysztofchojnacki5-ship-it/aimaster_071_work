# BUILD STATUS — 0.6.64-SOURCE — Companion revocation race hardening

**Typ:** poprawki i testy szkicu pkt 4; bez APK.
**Baza:** `0.6.63-SOURCE`.
**Aplikacja:** `versionName=0.6.54`, `versionCode=80` — bez zmian.

## Wykryte błędy i poprawki
1. `CompanionConnectionRegistry.register()` rozpatrywał konflikt właściciela przed sprawdzeniem aktualności biletu. Stary/replay ticket peera B mógł przez to odłączyć handle należący do peera A. Teraz nieaktualny ticket nie może zmieniać istniejącej własności.
2. Mapa `peerEpoch` mogła zachowywać wpisy po zakończeniu wszystkich sesji i ticketów. Dodano bezpieczne prunowanie epok peerów bez aktywnych zasobów.
3. `OwnedConnection.terminate()` mogło pominąć zamknięcie socketu i discard receipt-u, gdyby przejście stanu ownera rzuciło wyjątek. Cleanup jest teraz zagnieżdżony w `finally`.

## Walidacja wykonana
- Registry draft: **23/23 PASS** przez lokalny JVM harness z shimem adnotacji/asercji JUnit; NIE jest to JUnit ani Gradle.
- `kotlinc -Werror` dla registry + gate + rzeczywistych klas TLS/owner/protocol: PASS.
- Publisher: 2/2 PASS przez lokalny JVM harness (testy źródłowe publishera, nie JUnit/Gradle).
- 200 wyścigów register/revoke w testach registry: PASS; dokładnie jedno zamknięcie i jeden callback revocation na próbę.
- 500 cykli ticket/revoke/cancel: brak osieroconych epok.
- ZIP integrity (`unzip -t`): PASS; hash SHA-256 zapisany w raporcie końcowym.
- Gradle/JUnit/Robolectric/APK/Android Keystore/device: NOT RUN.

## Status bramek
- Registry/gate/binding nadal w `drafts/pkt4`, poza `app/src`.
- Store→registry binding nie jest podłączony produkcyjnie.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
- Wersja aplikacji pozostaje 0.6.54 / versionCode 80.

## Następny krok
Audyt granicy store→publisher→binding→registry: kolejność commit→epoch invalidation→socket close, równoległe revoke/wipe, wyjątki callbacków, i race podczas zamykania bindingu.
