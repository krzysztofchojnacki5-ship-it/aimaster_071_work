# 0.6.62-SOURCE — Companion gate loopback integration

**Typ:** test integracyjny gate → owner → registry na rzeczywistym loopback mTLS; bez APK.  
**Baza:** `0.6.61-SOURCE`.  
**Aplikacja:** `versionName=0.6.54`, `versionCode=80` — bez zmian.

## Zmiany
- Rozszerzono fixture testowy o client TLS profile, aby sprawdzić rzeczywisty przepływ przez `CompanionTlsHandshakeGate`.
- Nowy test wykonuje pairing receipt → gate.accept → TLS mTLS handshake → owner.activate → registry.register.
- Po `registry.revoke(peerId)` test sprawdza bez kolejnego wywołania API ownera: socket jest zamknięty, owner ma `FAILED`, identity jest wyczyszczone, registry nie ma aktywnych handle.
- Usunięto zbędny publiczny getter receipt z `OwnedConnection`; po gate activation receipt jest już skonsumowany i nie powinien być ponownie udostępniany.

## Walidacja wykonana
- Rzeczywiste klasy Kotlin core TLS + gate + registry, `kotlinc -Werror`: PASS.
- `CompanionSessionOwnerTest` z rzeczywistymi certyfikatami testowymi i loopback mTLS: 22/22 PASS przez lokalny JVM reflection harness; NIE jest to JUnit/Gradle.
- Store/core/gate/binding z atrapami Android/JSON, `kotlinc -Werror`: PASS.
- Registry draft: 16/16 PASS przez lokalny shim; publisher: 2/2 PASS.
- Gradle / Robolectric / APK / Android Keystore / urządzenie: NOT RUN.

## Ograniczenia
- `CompanionConnectionRegistry`, `CompanionTlsHandshakeGate` i `CompanionTrustRevocationBinding` nadal pozostają w `drafts/pkt4`, poza Android source set.
- Store publisher jest w produkcyjnym kodzie, lecz aplikacja nie instaluje jeszcze bindingu do registry.
- Test nie symuluje awarii Android Keystore ani wyścigu rzeczywistego callbacku store w Robolectric.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
