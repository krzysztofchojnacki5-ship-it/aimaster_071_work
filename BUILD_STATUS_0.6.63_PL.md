# BUILD STATUS — 0.6.63-SOURCE — Companion single-use admission tickets

**Typ:** hardening szkicu admission registry/gate; bez APK.  
**Baza:** `0.6.62-SOURCE`.  
**Aplikacja:** `versionName=0.6.54`, `versionCode=80` — bez zmian.

## Zmiany
- Admission ticket jest jednorazowy i identity-tracked.
- Oczekujące handshake admission są ograniczone do 32; `cancel()` zwalnia slot i chroni przed nieograniczonym wzrostem ticketów.
- `cancel(ticket)` usuwa oczekujący bilet, a gate wykonuje anulowanie w `finally` przy sukcesie i błędzie handshake/activation.
- Powtórna rejestracja tego samego handle pozostaje idempotentna.
- Poprawiono błędny komentarz kolejności operacji w `wipeAllTrust()`.

## Testy
- Registry: 19/19 PASS przez lokalny shim (NOT JUnit).
- Gate + registry `kotlinc -Werror`: PASS.
- Owner/TLS/gate loopback: 22/22 PASS przez lokalny JVM harness (NOT JUnit/Gradle).
- Publisher: 2/2 PASS przez lokalny JVM harness.
- Gradle, JUnit, Robolectric, APK, Keystore i urządzenie: NOT RUN.

## Blokady
Registry/gate/binding nadal poza `app/src`; brak aktywnego bindingu store→registry. Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
