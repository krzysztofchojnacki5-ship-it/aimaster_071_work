# 0.6.69-SOURCE — Companion store pre-commit fence

- Dodano kontrakt `CompanionTrustRevocationFenceListener` i osobny kanał fence w publisherze.
- `CompanionProvisioningStore.revokePeer()` i `wipeAllTrust()` rozpoczynają fence pod monitorem store przed commit.
- Binding wykonuje w tej fazie wyłącznie `registry.beginRevocation()`; zamykanie socketów jest odroczone do cleanup po wyjściu z monitora.
- Awaria przygotowania fence wstrzymuje mutację zaufania; częściowo przygotowane cleanup-y są mimo to wykonywane. Błąd zapisu nie przywraca sesji.
- Lifecycle bindingu śledzi rozpoczęte cleanup-y; `close()` czeka na ich zakończenie. Równoległe wywołania `RevocationBatch.close()` i `FenceBatch.close()` czekają na zakończenie pierwszego cleanup, zamiast wracać przedwcześnie.
- Walidacja: `kotlinc -Werror` store/core z atrapami Android/JSON PASS; registry/publisher/binding 36/36; owner/TLS/gate 25/25; dodatkowy test integracyjny `wipeAllTrust()` 1/1 PASS. Test potwierdza fence przed commit, deferred close po zwolnieniu monitora i cleanup mimo symulowanego błędu AndroidKeyStore po commit.
- Nadal wymagane: instalacja bindingu w lifecycle aplikacji, testy JUnit/Gradle/Android i testy na urządzeniu. APK nie powstał; `versionName=0.6.54`, `versionCode=80`.
