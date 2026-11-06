# 0.6.63-SOURCE — Companion single-use admission tickets

## Zmiany
- `CompanionConnectionRegistry.Ticket` jest teraz biletem jednorazowym, śledzonym po tożsamości obiektu. Reużycie biletu do przyjęcia kolejnego handle jest odrzucane i zasób zostaje zamknięty.
- Dodano `cancel(ticket)`; gate anuluje bilet w `finally`, również gdy handshake TLS lub aktywacja ownera zakończy się wyjątkiem. Zapobiega to pozostawianiu biletów po nieudanym handshake.
- Ponowna rejestracja tego samego, już posiadanego handle pozostaje idempotentna i nie zamyka aktywnego połączenia.
- Doprecyzowano komentarz `wipeAllTrust()`: commit pinów i próba usunięcia aliasu są wykonywane w jednej sekcji store, a globalny event jest publikowany po zwolnieniu monitora. Poprzedni komentarz błędnie opisywał kolejność alias-delete/revocation.

## Walidacja wykonana
- Registry + testy draft, `kotlinc -Werror`: PASS.
- Registry draft: 19/19 PASS przez lokalny shim JUnit-compatible; NIE jest to JUnit/Gradle.
- Gate + registry, `kotlinc -Werror` z friend-path do skompilowanego core: PASS.
- Owner/TLS/gate loopback suite z rzeczywistymi certyfikatami: 22/22 PASS przez lokalny JVM reflection harness; NIE jest to JUnit/Gradle.
- Publisher: 2/2 PASS przez lokalny JVM harness.
- Pełny Gradle/JUnit/Robolectric/APK/Android Keystore/urządzenie: NOT RUN.

## Status
- Zmiany registry/gate nadal pozostają w `drafts/pkt4`, poza produkcyjnym Android source set.
- Store→registry binding nadal nie jest instalowany w aplikacji.
- Aplikacja pozostaje `versionName=0.6.54`, `versionCode=80`; nie utworzono APK.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
