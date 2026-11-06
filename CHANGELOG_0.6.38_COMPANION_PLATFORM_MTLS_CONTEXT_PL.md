# AI MASTER PRO 0.6.38 — platformowy kontekst TLS/mTLS

## Zakres

Dodano `CompanionTlsContext`, mały adapter konfiguracyjny nad platformowym dostawcą JSSE/Android TLS. Nie implementuje własnego handshake, walidatora certyfikatów ani transportu aplikacyjnego.

## Zmiany

- `createMutualTlsContext()` wymaga magazynu tożsamości z wpisem klucza prywatnego i łańcuchem certyfikatów oraz niepustego magazynu zaufania.
- Klucze i zaufanie obsługują standardowe `KeyManagerFactory` i `TrustManagerFactory` dostawcy platformowego.
- Kontekst korzysta z `SSLContext.getInstance("TLS")` i `SecureRandom`; nie ma `TrustManager` akceptującego wszystkie certyfikaty.
- `openClientSocket()` tworzy połączenie przez fabrykę TLS dla wskazanego hosta, ogranicza protokoły do TLS 1.2/1.3 i ustawia `endpointIdentificationAlgorithm = HTTPS` przed handshake.
- `configureMutualTlsServerSocket()` wymaga certyfikatu klienta (`needClientAuth=true`) i ogranicza protokoły do TLS 1.2/1.3.
- Brak TLS 1.2, brak klucza prywatnego/łańcucha lub pusty trust store powodują fail-closed.
- Dodano cztery testy: wiązanie hosta + endpoint identification, odrzucenie błędnego hosta, wymaganie certyfikatu klienta i odrzucenie pustego magazynu tożsamości.

## Granice / czego ta wersja NIE robi

- Nie generuje ani nie provisionuje certyfikatów.
- Nie instaluje CA/pinów i nie realizuje SPKI pinning ani rotacji/unieważniania.
- Nie wykonuje handshake TLS w teście integracyjnym; nie ma jeszcze certyfikatów obu stron.
- Nie dodaje listenera, discovery, protokołu transportowego ani Desktop Companion.
- Nie łączy jeszcze tożsamości certyfikatu z `CompanionSessionOwner` / `VerifiedCompanionPairing`.
- Nie wolno jeszcze przesyłać bundle. `PROJECT_TESTS` pozostaje `NOT_RUN`.

## Weryfikacja

- Kotlin/JVM `-Werror`, wybrane klasy Companion + TLS: 55/55 PASS.
- Dziesięć pełnych powtórzeń zestawu: 550/550 PASS.
- XML manifestu i network security config: PASS (parsowanie).
- ZIP integrity: PASS.
- Pełny Gradle/Robolectric, APK i test urządzenia: NOT RUN.
