# CHANGELOG 0.6.56-SOURCE — przegląd integracji Companion pkt 4

- Wykonano statyczny audyt `CompanionPinnedTlsProfile`, `CompanionSessionOwner`, `CompanionProvisioningStore` oraz szkicu `CompanionConnectionRegistry`.
- Potwierdzono, że ticket epoki musi przejść przez accept/connect od chwili przed handshake; nie można pobrać go po handshake.
- Dodano sześć jawnych ryzyk integracyjnych: brak ticketu przed handshake, brak stanu terminalnego ownera przy zamknięciu socketu, ryzyko lock inversion callbacku store, kolejność `wipeAllTrust` przy błędzie Keystore, współdzielony `SSLServerSocket.soTimeout`, wyciek porzuconych receipt-ów TLS.
- Zapisano kontrakt kolejności operacji, reguły blokad i listę wymaganych testów JVM/TLS loopback/Android Keystore.
- Nie zmieniono produkcyjnego kodu aplikacji. Registry nadal jest draftem poza `app/src`.
- Nie wykonano nowych testów JUnit/Gradle ani builda APK. Nie deklaruje się PASS dla testów runtime.
- Aplikacja pozostaje `versionName=0.6.54`, `versionCode=80`; 0.6.56-SOURCE jest dokumentacyjnym kamieniem milowym.
