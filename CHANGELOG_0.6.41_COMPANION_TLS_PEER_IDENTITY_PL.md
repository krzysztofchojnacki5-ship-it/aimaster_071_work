# Changelog 0.6.41 — Companion TLS peer identity proof

## Zmiany

- Dodano `CompanionPinnedTlsProfile`, który sam tworzy i prywatnie przechowuje przypięty `SSLContext`; nie przyjmuje z zewnątrz dowolnych `SSLSession` ani socketów do wystawiania dowodów.
- Profil tworzy własne gniazda serwera lub zestawia połączenie klienta, wykonuje handshake TLS/mTLS i dopiero po jego powodzeniu wydaje nieprzezroczysty, jednorazowy `CompanionTlsPeerReceipt`.
- Potwierdzenie jest przechowywane w `IdentityHashMap`; obiekt skopiowany, obcy, wymyślony lub ponownie użyty nie przechodzi weryfikacji.
- Provisioning pinów jest mapowany per identyfikator peer (`peerId -> current/next SPKI pins`), a nie tylko do wspólnej puli. Po PKIX i pinningu TLS profil sprawdza, czy certyfikat odpowiada pinowi przypisanemu do deklarowanej tożsamości.
- Dowód wiąże identyfikator sesji parowania, identyfikator peer, rolę lokalnego endpointu, odcisk SPKI i dokładną sesję TLS (obiekt sesji oraz kopię jej ID).
- `CompanionSessionOwner` otrzymuje zaufany profil TLS w konstruktorze. `activate()` bez dowodu zwraca `TLS_IDENTITY_REQUIRED`; aktywacja wymaga jednorazowego dowodu z tego konkretnego profilu i zgodności `sessionId` oraz peer ID z wcześniej zweryfikowanym parowaniem.
- Profil ogranicza liczbę gniazd serwera (8) i nieodebranych potwierdzeń (32); dodano jawne `closeServer()`.
- Połączenie klienta ma jawny limit czasu zestawienia TCP (1–60 s), osobny od limitu handshake/odczytu.
- Zachowano TLS 1.2/1.3, PKIX, SPKI pinning i HTTPS SAN/hostname validation.

## Weryfikacja

- Kompilacja wybranych klas Kotlin/JVM z `kotlinc -Werror`: PASS.
- Testy `CompanionSessionOwnerTest` i `CompanionTlsContextTest`: 29/29 PASS.
- Powtórzenia całego zestawu: 5 × 29/29 = 145/145 PASS.
- Testy obejmują rzeczywiste handshake mTLS loopback, w tym integrację profilu z właścicielem sesji.
- Runner używa lekkich stubów adnotacji/assert JUnit; to nie jest uruchomienie Gradle/JUnit/Robolectric.
- Pełny Gradle/Robolectric: NOT RUN; wrapper nie pobrał Gradle 8.13 z powodu niedostępnego DNS `services.gradle.org`.
- APK / instalacja / test urządzenia Android: NOT RUN.
- Provisioning trwałych kluczy i pinów w Android Keystore: NOT IMPLEMENTED.
- Rotacja i unieważnianie pinów z zarządzaniem cyklem życia: NOT IMPLEMENTED.
- Transport Companion / Desktop Companion: NOT IMPLEMENTED.
- OS-level sandbox: NOT IMPLEMENTED.
- `PROJECT_TESTS`: NOT RUN.

## Pochodzenie

Wersja 0.6.41 została opracowana na bazie jawnie oznaczonej rekonstrukcji 0.6.40, która powstała z odzyskanych źródeł 0.6.39. Nie jest oparta na oryginalnym, niedostępnym bajtowo archiwum 0.6.40.
