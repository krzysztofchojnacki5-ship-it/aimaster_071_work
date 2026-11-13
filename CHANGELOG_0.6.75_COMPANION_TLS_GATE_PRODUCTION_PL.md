# Zmiany — 0.6.75-SOURCE

## Produkcyjna bramka admission TLS
- Promowano `CompanionTlsHandshakeGate` z `drafts/pkt4` do `app/src/main/java/pl/aimasterpro/app/core/`.
- Gate pobiera jednorazowy ticket rejestru **przed** `CompanionPinnedTlsProfile.accept()` / `connect()`, aktywuje `CompanionSessionOwner` na podstawie receipt z dokładnie tego profilu TLS, a następnie rejestruje wrapper `OwnedConnection`.
- Wrapper zamyka socket, usuwa nieodebrany receipt i zwalnia ownership. Revocation przełącza ownera terminalnie do `FAILED` przed próbą zamknięcia socketu.
- `CompanionRuntime` posiada jeden `tlsHandshakeGate` związany z własnym registry; `CompanionApplication.companionTlsHandshakeGate()` udostępnia wyłącznie bramkę należącą do runtime.

## Utwardzenie zamykania Runtime
- Audyt wykrył krytyczną lukę lifecycle: referencja do gate zachowana przez caller mogła próbować admission po `CompanionRuntime.close()`, ponieważ samo odpięcie bindingu nie zamykało rejestru.
- Dodano `CompanionConnectionRegistry.shutdown()`: zamyka rejestr admission, unieważnia wszystkie outstanding tickets, odłącza aktywne uchwyty i zamyka je poza lockiem.
- `CompanionRuntime.close()` najpierw zamyka registry i aktywne sesje, a następnie odłącza revocation binding; próbuje obu operacji nawet po błędzie pierwszej i zachowuje błędy wtórne jako suppressed.
- Po shutdown nowe tickety są odrzucane; ticket uzyskany przed shutdown nie może zarejestrować połączenia.

## Testy i walidacja lokalna
- Dodano realny loopback mutual-TLS test produkcyjnego gate: handshake, aktywacja ownera, registry ownership i zamknięcie socketu/owner po revocation.
- W tym samym scenariuszu dodano deterministyczny wyścig: callback walidacji zaufania wywołuje revocation po handshake, ale przed `register()`; gate musi odrzucić stary ticket, zamknąć połączenie i ustawić owner `FAILED`.
- Dodano test 40 nieudanych prób connect, potwierdzający zwalnianie ticketów w `finally` i brak wycieku ponad limit 32.
- Dodano test zamknięcia runtime: aktywne połączenia są zamykane, registry odrzuca nowe tickety, a wcześniej pobrany ticket nie dopuszcza późnej rejestracji.
- `kotlinc -Werror` dla wybranego Companion core/gate/runtime: PASS.
- Lokalny shim runner: 8/8 nowych testów gate/runtime PASS; istniejący zestaw registry/binding/runtime 35/35 PASS na zaktualizowanym core. Są to uruchomienia lokalnego JVM runnera, nie Gradle/JUnit.
- `CompanionApplication.kt` seam skompilowany `kotlinc -Werror` z atrapami Android/data API: PASS. Manifest XML parse: PASS.

## Ograniczenia
- Gradle 8.13, prawdziwy JUnit/Robolectric, `assembleDebug`, APK, Android Keystore i test na urządzeniu: NOT RUN.
- Bramka TLS jest dostępna jako produkcyjny komponent, ale aplikacja nie uruchamia automatycznie listenera ani połączeń. Brak aktywacji transportu sieciowego/UI.
- Wersja aplikacji bez zmian: `versionName=0.6.54`, `versionCode=80`. Paczka 0.6.75 jest paczką źródłową, nie APK.
