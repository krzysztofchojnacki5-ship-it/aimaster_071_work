# AI MASTER PRO 0.6.26 — status techniczny

## Zakres
- Android manifest: cleartext domyślnie wyłączony (`usesCleartextTraffic=false`) i jawnie podłączona konfiguracja `@xml/network_security_config`.
- Konfiguracja sieci: HTTP dopuszczone wyłącznie dla dokładnych hostów `localhost`, `127.0.0.1` i `10.0.2.2`; wszystkie pozostałe hosty dziedziczą zakaz cleartext.
- Nowa `CleartextEndpointPolicy` jest wspólnym, testowalnym źródłem decyzji klienta API; lista hostów jest zgodna z XML.
- `ProviderClient` odrzuca HTTP dla LAN/publicznych hostów, HTTP z kluczem API i dane uwierzytelniające w query. Automatyczne przekierowania są wyłączone.
- Nie dodano transportu companion ani nie zmieniono protokołu ramek.

## Weryfikacja
- Testy polityki hostów Kotlin/JVM z `-Werror`: **2/2 PASS**.
- XML `network_security_config.xml` i AndroidManifest: walidacja składni XML **PASS**.
- ZIP integrity: do wykonania po spakowaniu.
- Pełny Gradle `testDebugUnitTest`: **NOT RUN** — wrapper nie może pobrać Gradle 8.13, `UnknownHostException: services.gradle.org`.
- APK / instalacja / test na urządzeniu: **NOT RUN**.
- Testy `ProviderClientNetworkTest` na Android/Robolectric: **NOT RUN** z powodu braku dystrybucji Gradle.

## Zmiana zachowania / ograniczenia
- Niezabezpieczone endpointy HTTP w prywatnej sieci LAN (np. `192.168.x.x`, `10.x.x.x` poza aliasem emulatora) przestają działać. Wymagają HTTPS.
- Lokalny Ollama pod `localhost` / `127.0.0.1` oraz host emulatora `10.0.2.2` pozostają dozwolone, bez klucza API.
- Polityka cleartext nie zastępuje TLS/mTLS, walidacji certyfikatu ani provisionowania kluczy companion.
