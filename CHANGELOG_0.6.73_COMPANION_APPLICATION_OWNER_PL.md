# 0.6.73-SOURCE — Companion Application owner

## Zakres
- Dodano produkcyjną klasę `pl.aimasterpro.app.CompanionApplication` dziedziczącą po `Application`.
- Manifest wskazuje tę klasę przez `android:name=".CompanionApplication"`.
- Usunięto domyślne tworzenie store z konstruktora `CompanionTrustService`; każdy caller musi jawnie przekazać współdzielony store.
- `CompanionProvisioningStore` jest tworzony leniwie przez `lazy(SYNCHRONIZED)` z `applicationContext`, raz na instancję procesu/Application.
- Dodano fabrykę `companionTrustService()`, która przekazuje wspólny store do `CompanionTrustService`.
- Dwa call sites w `MainActivity` (odczyt stanu i jawne odzyskiwanie) korzystają z application-scoped service; usunięto bezpośrednie tworzenie `CompanionTrustService(this)`.
- Dodano testy Robolectric sprawdzające tożsamość store oraz współdzielenie go przez dwa serwisy.

## Granice i bezpieczeństwo
- To jest rzeczywista integracja application-scoped **store**, nie pełne uruchomienie transportu Companion.
- `CompanionRuntime`, registry i revocation binding nadal pozostają w `drafts/pkt4`; nie zostały podłączone do `Application` ani produkcyjnego source set.
- Brak automatycznego odtwarzania socketów, ticketów i sesji.
- Nie zmieniono `versionName=0.6.54` ani `versionCode=80`.

## Walidacja
- Statyczna kontrola manifestu i call sites: PASS; oba dotychczasowe miejsca UI korzystają z `CompanionApplication`.
- `./gradlew testDebugUnitTest --no-daemon`: NOT RUN — wrapper próbował pobrać Gradle 8.13, ale środowisko nie rozwiązuje `services.gradle.org` (`UnknownHostException`).
- `assembleDebug`, Robolectric, APK i test urządzeniowy: NOT RUN.
