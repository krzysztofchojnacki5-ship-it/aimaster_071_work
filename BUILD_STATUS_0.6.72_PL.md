# BUILD STATUS — 0.6.72-SOURCE

- Źródło bazowe: 0.6.71-SOURCE.
- Zakres: application-scoped graph w `drafts/pkt4`; DI seam dla `CompanionTrustService`.
- `AndroidManifest.xml`: bez custom `Application` (świadomie bez zmian).
- `MainActivity`: dwa call sites nadal używają konstruktora domyślnego; brak realnego współdzielenia w UI na tym etapie.
- Kotlin `-Werror` dla `CompanionTrustRevocation`, `CompanionConnectionRegistry`, `CompanionTrustRevocationBinding`, `CompanionRuntime`, `CompanionApplicationGraph` i testów graph: PASS.
- Graph harness: 3/3 PASS (współbieżna inicjalizacja, wspólny runtime/odtworzenie, retry po błędzie factory).
- ZIP `unzip -t`: PASS.
- Gradle/JUnit/Robolectric/assembleDebug/APK/Android Keystore/device: NOT RUN.
- App version pozostaje `0.6.54`, `versionCode=80`.
- Status: SOURCE DRAFT; nie jest to build instalacyjny.
