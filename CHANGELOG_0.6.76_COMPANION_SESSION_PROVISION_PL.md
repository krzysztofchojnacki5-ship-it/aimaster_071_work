# Zmiany — 0.6.76-SOURCE

## Spójny factory sesji TLS
- Dodano `CompanionProvisionedSession`: capability łączące konkretny `CompanionPinnedTlsProfile` z `CompanionSessionOwner` utworzonym na bazie dokładnie tej samej instancji profilu.
- Dodano `CompanionProvisioningStore.createSessionProvision(...)` jako preferowaną ścieżkę tworzenia sesji. Factory pobiera snapshot pinów z trwałego magazynu, tworzy profil TLS, tworzy owner z walidatorem bieżących pinów i zwraca nierozdzielny obiekt provisioningu.
- `CompanionTlsHandshakeGate` otrzymał warianty `accept/connect(CompanionProvisionedSession, ...)`, które nie wymagają osobnego przekazywania profilu i ownera.
- Gate weryfikuje tożsamość referencyjną profilu związanego z ownerem przed pobraniem admission ticket. Błędne połączenie ownera z innym profilem jest odrzucane przed rozpoczęciem handshake i bez zużycia limitu ticketów.
- Starsza metoda `createSessionOwner(...)` pozostaje dla kompatybilności, ale jest oznaczona `@Deprecated`; zalecany jest `createSessionProvision(...)`.

## Testy
- Zaktualizowano loopback mutual-TLS test tak, aby ścieżka sukcesu korzystała z `CompanionProvisionedSession` i nowego overloadu gate.
- Dodano test odrzucenia profilu innego niż profil związany z ownerem; obejmuje też kontrolę, że gate odrzuca mismatch przed przydzieleniem ticketu.
- `kotlinc -Werror` dla produkcyjnego Companion core: PASS.
- Lokalny runner JVM z shimem JUnit: gate 3/3 + runtime 6/6 = 9/9 PASS. Loopback mTLS używa testowych certyfikatów z zasobów projektu.

## Ograniczenia
- Pełna kompilacja Android/Gradle i kompilacja klasy `CompanionProvisioningStore` w Android toolchain: NOT RUN.
- Gradle/JUnit/Robolectric/assembleDebug/APK/Android Keystore/device: NOT RUN.
- Nie uruchomiono listenera ani transportu Companion. Wersja aplikacji pozostaje `0.6.54`, `versionCode=80`.
