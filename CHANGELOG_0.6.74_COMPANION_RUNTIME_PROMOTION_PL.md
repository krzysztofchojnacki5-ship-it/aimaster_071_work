# Zmiany — 0.6.74-SOURCE

## Produkcyjny Companion runtime
- Przeniesiono `CompanionConnectionRegistry`, `CompanionTrustRevocationBinding`, `CompanionRuntime` i `CompanionRuntimeSlot` z `drafts/pkt4` do `app/src/main/java/pl/aimasterpro/app/core/`.
- `CompanionApplication` jest teraz właścicielem jednego `CompanionRuntimeSlot`; `companionRuntime()` tworzy runtime leniwie i współdzieli dokładnie `companionProvisioningStore`.
- `CompanionTrustService` nadal otrzymuje jawnie ten sam application-scoped store.
- Runtime zawiera wyłącznie pamięciowy registry i listener revocation. Nie otwiera transportu, nie przyjmuje socketów i nie odtwarza sesji/ticketów po restarcie procesu.

## Testy
- Przeniesiono testy registry do Android test source set.
- Dodano adnotacje JUnit `@Test` do testów Runtime i Binding, które wcześniej były metodami niewykrywanymi przez runner.
- Dodano testy Application: współbieżne uzyskiwanie tej samej instancji runtime, dokładna tożsamość źródła bindingu ze store oraz pojedynczy fence listener.
- Lokalna walidacja: `kotlinc -Werror` dla czterech produkcyjnych klas core PASS; 35/35 metod testowych registry/binding/runtime PASS w lokalnym runnerze JVM z lekkim shimem JUnit. To nie jest wykonanie JUnit/Gradle.
- Gradle 8.13, Robolectric, `assembleDebug`, APK, Android Keystore i urządzenie: NOT RUN.

## Ograniczenia
- `CompanionTlsHandshakeGate` pozostaje poza produkcyjnym source set. Nie ma jeszcze produkcyjnego przepływu akceptowania/otwierania połączeń.
- `CompanionApplicationGraph` pozostaje historycznym szkicem; rzeczywistym właścicielem jest `CompanionApplication`.
- Wersja aplikacji pozostaje `0.6.54` / `versionCode=80`; paczka 0.6.74 jest paczką źródłową, nie APK.
