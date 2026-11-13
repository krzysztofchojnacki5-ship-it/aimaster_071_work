# 0.6.26 — Cleartext hardening

### Zmiany
- Globalny cleartext HTTP został wyłączony w manifeście Androida.
- Dodano `network_security_config.xml` z domyślnym zakazem cleartext i trzema dokładnymi wyjątkami: `localhost`, `127.0.0.1`, `10.0.2.2`.
- Dodano `CleartextEndpointPolicy` oraz testy pozytywne i negatywne, w tym próby obejścia przez podobne nazwy hostów i nieprawidłowe adresy IP.
- `ProviderClient` korzysta z allowlisty, odrzuca HTTP z kluczem API i wyłącza automatyczne przekierowania.

### Powód
Audyt manifestu ujawnił `usesCleartextTraffic=true`, które otwierało HTTP dla całej aplikacji, mimo że klient dostawców miał własną, węższą walidację. Ujednolicono politykę aplikacji i klienta.

### Kompatybilność
HTTP do adresów LAN jest teraz blokowane. Dla Ollama na innym urządzeniu w sieci wymagany jest HTTPS; lokalny loopback oraz `10.0.2.2` emulatora pozostają obsługiwane.

### Status
Testy Kotlin/JVM polityki hostów 2/2 PASS. Pełny Gradle, Robolectric, APK i test urządzeniowy NOT RUN z powodu niedostępności `services.gradle.org`.
