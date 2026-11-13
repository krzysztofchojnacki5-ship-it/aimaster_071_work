# Changelog 0.6.39 — rzeczywiste testy handshake mTLS

- Dodano testowe certyfikaty X.509 CA, serwera i klienta wyłącznie w `app/src/test/resources/companion-tls/`. Klucze prywatne w tym katalogu są fikcyjnymi fixture testowymi; nie wolno używać ich w produkcji. Klucze prywatne CA nie są dołączone.
- Wykonano rzeczywisty handshake TLS/mTLS na loopback z platformowym `SSLContext`, `SSLSocket` i `SSLServerSocket`.
- Potwierdzono pozytywny handshake z zaufanymi certyfikatami klienta i serwera, w tym osobno TLS 1.2. Test domyślny akceptuje TLS 1.2 lub TLS 1.3 zgodnie z negocjacją dostawcy.
- Testy negatywne potwierdzają odrzucenie: klienta bez certyfikatu, klienta z łańcuchem od niezaufanego CA, serwera z błędnym SAN oraz serwera z łańcuchem od niezaufanego CA.
- Testy negatywne oceniają stronę, która wykonuje walidację certyfikatu; w TLS 1.3 klient może nie otrzymać synchronicznie informacji o odrzuceniu własnego certyfikatu, dlatego dla certyfikatu klienta rozstrzygający jest wynik handshake serwera.
- Testy Kotlin/JVM z `-Werror`: 61/61 PASS; 10 powtórzeń: 610/610 PASS.
- To nadal testy JVM na loopback, nie test urządzeniowy Androida ani integracja transportu Companion. Nie wdrożono provisioningu, pinningu SPKI, rotacji/unieważniania, związania certyfikatu peer z `CompanionSessionOwner`, listenera sieciowego ani Desktop Companion.
