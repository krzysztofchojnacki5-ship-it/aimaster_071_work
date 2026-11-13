# Projekt 5.1 — cykl życia certyfikatu TLS Companion (wydanie, odnowienie, unieważnienie)

Status: PROJEKT. Brak zmian w kodzie produkcyjnym. Nic z poniższego nie jest zweryfikowane na urządzeniu ani w Gradle (NOT RUN).

## 1. Stan obecny (fakty z kodu)
- `CompanionProvisioningStore.provisionLocalIdentity()` tworzy klucz EC w Android Keystore i certyfikat **samopodpisany** (CN=AI MASTER Companion, bez SAN, seryjny 1, ważny do 2100). Ten certyfikat służy jako tożsamość do podpisu wyzwania (`signChallenge`) i NIE jest certyfikatem TLS.
- `CompanionTlsContext` wymaga klucza z łańcuchem certyfikatów i jawnego trust store, a klient ustawia `endpointIdentificationAlgorithm=HTTPS`, więc SAN musi pasować do nazwy hosta.
- `CompanionPinnedTlsProfile` dokłada przypięcie SPKI SHA-256 (current/next) na walidację łańcucha, a nie zamiast niej.
- Brak: CA, który wydaje certyfikaty TLS; brak procesu CSR; brak SAN w tożsamości TLS.

## 2. Dwie oddzielne tożsamości (decyzja)
1. **Tożsamość wyzwania** (istniejąca): klucz Keystore, samopodpisany, tylko do podpisu wyzwań pairing. Bez zmian.
2. **Tożsamość TLS** (nowa): osobny klucz EC P-256 w Android Keystore, nieeksportowalny, z purpose `SIGN` (TLS używa go do CertificateVerify) oraz certyfikat **wydany przez CA Companion** z SAN.

Zasada: samopodpisanego certyfikatu z pkt 1 nigdy nie używa się w TLS. Walidator musi odrzucać certyfikat, którego wystawca równa się podmiotowi (self-signed) w ścieżce TLS.

## 3. Model zaufania
- **Root CA Companion** żyje na komputerze Desktop Companion, klucz prywatny zaszyfrowany at-rest (hasło użytkownika lub OS keychain). Nigdy nie opuszcza desktopu i nie trafia do bundle ani logów.
- Root CA: ważność 10 lat. Leaf TLS: ważność 30 dni (krótka, by ograniczyć skutki kradzieży klucza i upraszczać rotację).
- Android trust store zawiera wyłącznie root CA sparowanego Companion. Brak trust store systemowego dla tej ścieżki.
- Pin SPKI leafa pozostaje warstwą dodatkową (istniejący mechanizm `CompanionPeerPinSet`), a nie zamiennikiem łańcucha.

## 4. Wydanie certyfikatu (issuance)
1. Android generuje klucz TLS w Keystore (alias `ai_master_companion_tls_v{N}`), nieeksportowalny, EC P-256.
2. Android buduje CSR (PKCS#10) podpisany kluczem Keystore. CSR zawiera: CN=`<clientId>`, SAN=lista dozwolonych nazw z pairing, public key.
3. CSR idzie wyłącznie przez już uwierzytelniony kanał pairing (PSK challenge-response + ramki z `CompanionFrameProtocol`). Bez uwierzytelnienia — odrzucenie.
4. Desktop CA waliduje CSR: algorytm EC P-256, podpis CSR poprawny, SAN zgodne z listą zatwierdzoną przy parowaniu, brak dodatkowych SAN i wildcardów, EKU `serverAuth` i `clientAuth`, ważność 30 dni, unikalny numer seryjny z licznika.
5. Desktop zwraca leaf + łańcuch CA (wyłącznie certyfikaty publiczne).
6. Android weryfikuje odpowiedź: klucz publiczny leafa = klucz Keystore z CSR, łańcuch do root CA sparowanego, daty, SAN, EKU. Dopiero po tym zapisuje certyfikat.

Odpowiedź z błędną walidacją kończy operację i nie zapisuje niczego (fail-closed).

## 5. Przechowywanie
- Klucz prywatny: wyłącznie Android Keystore.
- Certyfikat leafa i łańcuch: są publiczne, mogą być w zaszyfrowanych prefs (AAD: `AI_MASTER_COMPANION_TLS_CHAIN_V1`), żeby nie wymagać przeniesienia klucza.
- Do potwierdzenia na urządzeniu: czy `KeyStore.setKeyEntry` z kluczem z AndroidKeyStore i nowym łańcuchem jest akceptowane. Jeśli nie, używamy tymczasowego `KeyStore` w pamięci składanego z klucza Keystore i zapisanego łańcucha przy każdym handshake. Decyzja zostanie podjęta w pkt 5.1b po teście na urządzeniu.

## 6. Odnowienie (rotacja)
- Odnowienie zaczyna się przy 2/3 okresu ważności (dzień 20 z 30) i zawsze wykorzystuje nowy klucz (`_v{N+1}`), nie ponownie użytego.
- Nowy SPKI trafia do `CompanionPeerPinSet.stage()`, a dopiero po udanym handshake z nowym leafem wykonywane jest `promotePeerRotation()`. Stary pin działa w czasie nakładania.
- Jeśli odnowienie się nie uda, leaf wygasa i sesja jest fail-closed. Dopóki nie ma kanału pairing, nie ma ponownego wydania.
- Stary klucz Keystore jest usuwany dopiero po promocji i wygaśnięciu starego leafa.

## 7. Unieważnienie
- Unieważnienie peera = `revokePeer()` usuwa piny (istniejący mechanizm). Sesja odrzuca peera przy następnej operacji (`PEER_REVOKED`).
- Brak CRL/OCSP: prywatne CA, a autorytatywnym źródłem jest magazyn pinów. Kompromitacja root CA wymaga ponownego parowania wszystkich peerów (opisane w pkt 9).
- Usunięcie klucza TLS z Keystore unieważnia łańcuch lokalnie, bo nie ma klucza do handshake. Wymagane jest ponowne wydanie przez kanał pairing.

## 8. Wymagania negatywne (testy przyszłe, NIE uruchomione)
- Leaf samopodpisany → odrzucony.
- Leaf wydany przez obcy CA → odrzucony.
- SAN niezgodny z hostem → odrzucony.
- Wygasły lub jeszcze nieważny leaf → odrzucony.
- Klucz publiczny leafa różny od klucza Keystore w CSR → odrzucony.
- CSR bez podpisu lub z algorytmem innym niż EC P-256 → odrzucony przez CA.
- CSR wysłany bez uwierzytelnionego pairing → odrzucony.
- Wildcard lub dodatkowy SAN w CSR → odrzucony przez CA.
- Odnowienie z kluczem już użytym → odrzucone.

## 9. Recovery i kompromitacja
- Utrata klucza Keystore, łańcucha lub root CA: brak automatycznego odtworzenia zaufania. Wymagane jawne ponowne parowanie (pkt 3 listy przekazania, osobny krok).
- Kompromitacja root CA: wszystkie piny i leafy traktowane jako nieaktualne; ponowne parowanie z nowym root CA.

## 10. Zależności, ryzyka i decyzje do podjęcia
- Generowanie CSR wymaga biblioteki lub ręcznego kodowania DER. JDK nie ma publicznego API do PKCS#10. Dodanie zależności (np. BouncyCastle) wymaga dostępu do repozytoriów w Gradle, którego ten etap nie miał. **Decyzja użytkownika potrzebna przed pkt 5.1b.**
- Po stronie desktopu CA nie jest jeszcze zaprojektowane (język, przechowywanie klucza). Ten projekt zakłada, że CA istnieje i opisuje tylko kontrakt.
- Walidację odpowiedzi CA można napisać jako czyste funkcje Kotlina na `X509Certificate` i testować na JVM z certyfikatami z `app/src/test/resources/companion-tls/`, bez Keystore.

## 11. Kryteria zamknięcia pkt 5.1
- Ten dokument zatwierdzony przez użytkownika.
- Decyzja o generowaniu CSR (zależność lub DER) podjęta.
- Decyzja o składaniu łańcucha w Keystore potwierdzona na urządzeniu (pkt 5).
