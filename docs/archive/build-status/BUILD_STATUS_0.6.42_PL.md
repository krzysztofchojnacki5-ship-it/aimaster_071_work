# AI MASTER PRO 0.6.42 — status wydania

## Zakres
Częściowa realizacja pozycji 5 listy Companion: Android Keystore i trwała mapa zaufania peer.

## Zmiany
- `CompanionProvisioningStore` tworzy lokalną parę EC P-256 w `AndroidKeyStore`; prywatny klucz nie jest eksportowany.
- `signChallenge()` podpisuje challenge ECDSA/SHA-256 kluczem z Keystore.
- Mapa peer ID → current/next SPKI jest szyfrowana AES-256-GCM z AAD i przechowywana w app-private preferences.
- Zapis jest synchroniczny (`commit`) i sprawdza wynik.
- Utrata klucza AES przy obecności zaszyfrowanych danych powoduje jawny błąd recovery; nie następuje automatyczne odtworzenie pustego magazynu.
- Rotacja obsługuje stage/promote/cancel; istniejący staged pin nie może zostać cicho nadpisany.
- Revocation usuwa peer i oba piny. `readTlsPinsByPeerId()` zwraca mapę gotową do przekazania do `CompanionPinnedTlsProfile.create()`.

## Walidacja
- Testy `CompanionPeerPinSetTest`: **5/5 PASS**, kompilacja `kotlinc -Werror`; 5 powtórzeń: **25/25 PASS**.
- Regresja TLS/owner z poprzedniego wydania: **29/29 PASS**; 5 powtórzeń: **145/145 PASS**.
- Regresja owner: **14/14 PASS**; 5 powtórzeń: **70/70 PASS**.
- Nowy `CompanionProvisioningStore.kt` przeszedł kontrolę składni i typów Kotlin z lokalnymi atrapami API Android/JSON; to nie zastępuje kompilacji z prawdziwym Android SDK.
- Archiwum ZIP: do sprawdzenia przed publikacją.
- Próba `./gradlew :app:compileDebugKotlin --no-daemon`: **BLOCKED** przed kompilacją — wrapper nie mógł pobrać Gradle 8.13 (`UnknownHostException: services.gradle.org`). Android Gradle/Robolectric: **NOT RUN**.
- APK / instalacja / urządzenie: **NOT RUN**.
- `PROJECT_TESTS`: **NOT RUN**.

## Ograniczenia i otwarte bramki
- Certyfikat generowany przez Android Keystore jest lokalnym certyfikatem tożsamości klucza, nie certyfikatem TLS z poprawnym SAN. Nie wolno używać go jako gotowego certyfikatu serwera TLS bez zaufanego procesu wydania/uzupełnienia łańcucha i SAN.
- Provisioning nie jest jeszcze spięty z `CompanionPinnedTlsProfile` ani `CompanionSessionOwner`.
- Nie wdrożono migracji istniejącego PSK, procedury recovery/re-pairing, eksportu/importu kontrolowanego zaufania ani testów na rzeczywistym Android Keystore.
- Revocation wpływa na nowe profile TLS po ponownym odczycie magazynu; aktywne profile/sesje muszą zostać zamknięte i odtworzone przez przyszłą integrację.
- Transport Companion, Desktop Companion, OS sandbox i `PROJECT_TESTS` pozostają zablokowane.

## Pochodzenie źródeł
Baza robocza 0.6.41 pochodzi z rekonstrukcji 0.6.40 na podstawie odzyskanego 0.6.39. To wydanie kontynuuje tę bazę; nie jest oryginalnym, odzyskanym archiwum 0.6.40.
