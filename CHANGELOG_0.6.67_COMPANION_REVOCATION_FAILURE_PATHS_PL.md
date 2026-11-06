# 0.6.67-SOURCE — Companion revocation failure-path hardening

- `revokePeer()` publikuje revocation również wtedy, gdy odczyt/zapis magazynu kończy się błędem lub wynikiem niejednoznacznym. Aktywne sesje są w takiej sytuacji zamykane fail-closed, a błąd trwałego usunięcia peera jest raportowany osobno.
- `wipeAllTrust()` publikuje globalne revocation po każdej próbie wipe, także gdy commit SharedPreferences zwróci false lub rzuci wyjątek. Usuwanie aliasu Keystore następuje tylko po potwierdzonym usunięciu pinów.
- Błąd alias cleanup jest przechowywany jako `Throwable`; także fatalny `Error` nie może ominąć dispatchu. Po próbie wszystkich listenerów fatalny błąd jest ponownie rzucany, z pozostałymi błędami jako suppressed.
- `CompanionSessionOwner.isPeerTrusted()` czyści sesję i ustawia terminalny `FAILED` przed propagacją fatalnego `Error` z walidatora podczas operacji na już aktywnej sesji.
- Test ownera: fatalny validator w `beginTransfer()` powoduje `FAILED`, usunięcie identity i brak ujawnienia TLS peer identity.
- Walidacja: 55/55 metod lokalnego JVM harnessu PASS; `kotlinc -Werror` PASS dla core, store z atrapami Android/JSON i szkicu pkt4.
- Gradle/JUnit/Robolectric/APK/Android Keystore/device: NOT RUN.
- `versionName=0.6.54`, `versionCode=80`; to paczka źródłowa, nie APK.
- Registry/gate/binding nadal draft. Atomowa integracja store→registry pozostaje OPEN.
