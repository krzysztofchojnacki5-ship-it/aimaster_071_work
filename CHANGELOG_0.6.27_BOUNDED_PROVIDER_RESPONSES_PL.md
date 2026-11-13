# AI MASTER PRO 0.6.27 — bounded provider responses

## Audyt i zmiana
- Audyt 0.6.26 wykazał, że odpowiedzi HTTP dostawców były odczytywane przez `readText()` bez limitu rozmiaru.
- Dodano `BoundedResponseReader`: odczyt strumieniowy z twardym limitem 4 MiB, bez buforowania odpowiedzi ponad limit.
- Odpowiedzi przekraczające limit kończą się kategorią `RESPONSE_TOO_LARGE`.
- Niepoprawne UTF-8 jest odrzucane jako `INVALID_RESPONSE_ENCODING` zamiast zastępowania błędnych bajtów znakami zastępczymi.
- Obsłużono również nietypowy strumień zwracający zero bajtów dla odczytu z dodatnią długością.
- Zachowano dotychczasową politykę HTTP, retry, anulowanie żądań i format odpowiedzi JSON.

## Weryfikacja
- Kotlin/JVM kompilacja nowego readera z `-Werror`: PASS.
- Testy graniczne: 4/4 PASS (limit dokładny, przekroczenie limitu, niepoprawne UTF-8, pusty strumień).
- Pełne testy Gradle / ProviderClientNetworkTest: NOT RUN.
- APK / urządzenie Android: NOT RUN.

## Ograniczenia
- Limit 4 MiB dotyczy pojedynczej odpowiedzi HTTP dostawcy. Nie jest limitem transferu Companion ani rozmiaru bundle.
- Nie dodano TLS/mTLS ani transportu Companion.
