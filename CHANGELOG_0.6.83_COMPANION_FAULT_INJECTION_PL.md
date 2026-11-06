# 0.6.83-SOURCE — Companion fault injection i granice crash consistency

- Baza: 0.6.82-SOURCE.
- Dodano wewnętrzny `faultInjector` do `CompanionProvisioningStore`, domyślnie nieaktywny w produkcji.
- Oznaczono punkty przed/po commitach preferences, zapisie pinów, atomowym rename, fsync katalogu, unlink markera oraz próbie usunięcia aliasu metadata Keystore.
- Dodano dwa testy Robolectric pokrywające cztery granice publikacji i cztery granice usuwania markera. Testy dodane, ale NOT RUN.
- Dodano hostowy harness NIO z wstrzykiwaniem awarii: 200/200 przypadków PASS na JDK 21/Linux. Harness jest niezależnym modelem kolejności operacji plikowych, nie testuje Android store.
- Nie zmieniono wersji aplikacji (`0.6.54`, code `80`), nie utworzono APK i nie uruchomiono transportu.
- Ograniczenie: to seam granic wyjątków, nie pełny adapter I/O; `commit() == false` i awaria wewnątrz Keystore wymagają kolejnego etapu.
