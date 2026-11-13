# 0.6.66-SOURCE — Companion close race i fatal dispatch hardening

## Zmiany
- `CompanionConnectionRegistry` rezerwuje tożsamość uchwytu od chwili odłączenia aż do zakończenia zamykania poza blokadą. Równoległa rejestracja tego samego obiektu nie może już przyjąć go ponownie, zanim spóźnione `close()` starej sesji zostanie wykonane.
- Rezerwacja jest zwalniana w `finally`, również gdy zamykanie zgłosi wyjątek lub fatalny `Error`.
- `revoke()` próbuje zamknąć wszystkie odłączone uchwyty także wtedy, gdy jeden callback rzuci `Error`; po wykonaniu wszystkich prób ponownie rzuca pierwszy fatalny błąd. Nie jest on po cichu połykany.
- `CompanionTrustRevocationPublisher` analogicznie próbuje wywołać wszystkich listenerów po fatalnym `Error`, a dopiero potem propaguje fatalny błąd. Zwykłe wyjątki listenerów nadal są agregowane i zwracane.
- `CompanionSessionOwner.activate()` przy fatalnym błędzie walidatora czyści dane sesji i przechodzi do terminalnego `FAILED`, zanim ponownie rzuci `Error`.
- Gate po nieudanej/wyjątkowej aktywacji zawsze zamyka ownera, socket i receipt, zachowując pierwotny błąd i dopisując błędy cleanup jako suppressed.
- Wykryto, że `SSLSocket.close()` może blokować się na oczekiwaniu na TLS `close_notify` od nieufnego peer-a. Dodano bounded close: krótki timeout odczytu, zamknięcie inbound i dopiero close; zastosowano go do receipt discard/expiry i gate.
- Dodano testy regresyjne: ponowne przyjęcie uchwytu podczas trwającego close, kontynuowanie zamykania po `AssertionError`, dispatch wszystkich listenerów po fatalnym błędzie oraz loopback mTLS gate cleanup po fatalnym validatorze.

## Walidacja lokalna
- `kotlinc -Werror`: PASS dla publishera, registry, bindingu oraz testów.
- Lokalny JVM harness z lekkim shimem JUnit: **54/54 PASS** (25 registry, 3 publisher, 2 binding lifecycle, 24 owner/TLS/loopback gate). To nie jest uruchomienie JUnit ani Gradle.
- W teście blokującym zamknięcie nowy ticket nie może ponownie zarejestrować tej samej tożsamości uchwytu; po zakończeniu zamknięcia licznik aktywnych połączeń pozostaje 0.
- W teście fatalnego `Error` kolejne uchwyty/listenery otrzymują próbę zamknięcia/unieważnienia, a pierwszy fatalny błąd jest propagowany.
- Loopback mTLS: gate zamyka połączenie i ustawia owner `FAILED`, gdy trust validator rzuca fatalny `Error`; test potwierdził brak aktywnych uchwytów w registry.
- Wymuszony błąd ujawnił blokowanie `SSLSocket.close()` podczas oczekiwania na `close_notify`; bounded close skrócił tę ścieżkę i test loopback przeszedł.

## Ograniczenia
- Gradle/JUnit/Robolectric/APK/Android Keystore/test urządzenia: NOT RUN.
- Registry/gate/binding pozostają w `drafts/pkt4`; nie są zintegrowane z aktywnym lifecycle aplikacji.
- `versionName=0.6.54`, `versionCode=80`; nie utworzono APK.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
