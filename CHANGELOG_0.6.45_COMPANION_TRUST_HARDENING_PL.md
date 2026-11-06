# Changelog — AI MASTER PRO 0.6.45

## Utwardzenie egzekwowania zaufania Companion (przegląd 0.6.44)
- `tlsPeerIdentity()` sprawdza timeouty przed zwróceniem tożsamości TLS. Sesja przeterminowana (np. bezczynność) nie ujawnia już metadanych peera i przechodzi do `FAILED`.
- `failInternal()` i `closeInternal()` czyszczą `verifiedIdentity`. Wcześniej tylko `verifiedTlsPeer` był czyszczony, mimo że changelog 0.6.44 deklarował czyszczenie metadanych sesji.
- Kod odpowiedzi przy timeoucie podczas `acceptPairingReceipt` ujednolicony do `SESSION_TIMEOUT` (wcześniej `TIMEOUT`).
- Dodano dwa testy regresyjne: `tlsPeerIdentityIsNotExposedAfterIdleTimeout` oraz `pairingIdentityIsClearedWhenPeerRevoked`.

## Zmienione pliki
- `app/src/main/java/pl/aimasterpro/app/core/CompanionSessionOwner.kt`
- `app/src/test/java/pl/aimasterpro/app/core/CompanionSessionOwnerTest.kt`
- `app/build.gradle.kts` (versionCode 71, versionName 0.6.45)

## Granice
- Zmiany wykonano przeglądem statycznym. Nie uruchomiono kompilacji ani testów (brak Kotlin/Gradle/Android SDK w środowisku).
- Problemy punktu 5 z listy przekazania (certyfikat TLS z SAN/CA, migracja PSK, recovery/re-pairing, zamykanie socketu) pozostają otwarte.
- Otwarte ryzyko: brak ochrony przed wycofaniem stanu magazynu (rollback). Usunięcie wpisu pinów przy zachowanym kluczu jest odczytywane jako pusty magazyn, a nie jako błąd.
