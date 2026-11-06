# AI MASTER PRO — projekt bezpieczeństwa transportu Companion (0.6.30)

## Status

To jest projekt architektury i lista warunków wejścia do implementacji. **Nie oznacza wdrożenia TLS, mTLS, desktop Companion, socketów, discovery ani sandboxa.** W 0.6.28 nadal nie wolno przesyłać bundle przez sieć z użyciem samych ramek HMAC.

## Wynik audytu obecnych warstw

1. `CompanionPairingAuthority` wiąże challenge z klientem, sesją, nonce i terminem ważności opartym na zegarze monotonicznym procesu (odpornym na zmianę zegara ściennego); poprawny proof jest jednorazowy i weryfikowany stałoczasowo. Od 0.6.29 nieudane próby (zły sekret/MAC lub zmieniony uwierzytelniacz) nie zużywają oczekującego challenge, więc nie mogą go zdalnie unieważnić bez poprawnego uwierzytelnienia. Sekret 16–64 bajty musi być dostarczony poza kanałem. `expiresAt` jest lokalnym, monotonicznym terminem Androida (nie datą Unix); Companion przenosi tę wartość w proof, ale nie porównuje jej z własnym zegarem. Ostateczną ważność sprawdza wystawca challenge. Brakuje bezpiecznego provisionowania i transportu.
2. `CompanionFrameProtocol` ma zamknięte typy, osobne klucze kierunkowe, digest payloadu, ścisłą sekwencję i ochronę przed replay. HMAC daje integralność i uwierzytelnienie, **nie poufność**. Odbiornik nie jest jeszcze pełną maszyną stanów sesji (np. nie egzekwuje semantyki `CLOSE` ani limitu czasu życia); nie wolno podłączać go bezpośrednio do socketu.
3. `CompanionFrameWireCodec` ogranicza rozmiary przed alokacją, odrzuca błędny UTF-8, nieznane enumy, niestandardowy Base64URL i trailing bytes. Dekodowanie nie zastępuje weryfikacji HMAC.
4. `ExecutorProtocol` opisuje allowlistę zadań, zasoby i podpisany receipt, ale nie wykonuje procesów i nie dowodzi, że sandbox istnieje. Pola `sandboxVerified` / `networkDisabled` są deklaracją receipt i same nie tworzą izolacji.
5. `network_security_config.xml` blokuje cleartext domyślnie. Provider endpoint policy 0.6.28 dodatkowo odrzuca nieobsługiwane schematy, hosty bez nazwy, nieprawidłowe porty i user-info w URL przed otwarciem połączenia.

## Docelowa architektura transportu

### 1. TLS jako jedyna warstwa sieciowa

- Preferowany jest dojrzały stos TLS platformy Android/JVM, nie własny szyfr ani własny handshake.
- Minimalny protokół: TLS 1.2; TLS 1.3 preferowany, jeśli dostępny. Nie obniżać poziomu bezpieczeństwa przez fallback do cleartext.
- Wymagać walidacji certyfikatu, dat ważności, łańcucha zaufania, SAN/hostname oraz przypięcia tożsamości Companion (najlepiej SPKI pin z kontrolowaną rotacją).
- Docelowo mTLS: klient Android i Companion uwierzytelniają się wzajemnie. Klucz prywatny Androida powinien być generowany/przechowywany w Android Keystore; nie eksportować go do bundle ani logów.
- Nie stosować `TrustManager`, który akceptuje każdy certyfikat, globalnego `HostnameVerifier { _, _ -> true }`, ani wyłączania walidacji TLS.
- Jeśli Companion używa lokalnego certyfikatu self-signed, zaufanie musi być ograniczone do jawnie sparowanej tożsamości i kontrolowanej rotacji; nie wolno wyłączać walidacji hosta/dat.

### 2. Pairing i provisionowanie

- Kod/QR parowania: jednorazowy, krótko ważny, z wysoką entropią; ekran Companion pokazuje tożsamość urządzenia i fingerprint do potwierdzenia.
- Ustanowić zaufanie do certyfikatu przez fingerprint/SAS potwierdzony przez użytkownika poza kanałem, a następnie wykonać wzajemne uwierzytelnienie TLS.
- PSK nie może być wysyłany w plaintext, query URL, logach ani telemetrii. Jeśli pozostanie potrzebny do jednorazowego bootstrapu, musi być krótko ważny, ograniczony liczbą prób i usunięty po ustanowieniu trwałej tożsamości.
- Przechowywać klucze w Keystore/OS credential store; zapewnić unieważnienie pary, rotację, odnowienie i usuwanie kluczy.
- Związać sesję z `clientId`, `companionId`, certyfikatem, wersją protokołu i nonce. Odrzucać downgrade wersji.

### 3. Ramki i transfer bundle

- Ramki HMAC mogą pozostać dodatkową warstwą integralności/replay wewnątrz TLS, ale nie zastępują TLS. Jeśli zachowujemy własne klucze ramki, wyprowadzać je standardowym HKDF-SHA-256 z jednoznacznym salt/info i osobnymi etykietami kierunku.
- Egzekwować maszynę stanów: `CONNECTED → PAIRED → TRANSFERRING → COMPLETED/CLOSED`; błędna kolejność, `CLOSE`, błąd uwierzytelnienia lub timeout kończy sesję.
- Limit pojedynczego payloadu pozostaje 1 MiB; transfer dzielić na chunk-i z numerem, offsetem i rozmiarem. Cały bundle maksymalnie 40 MiB i maksymalnie 3000 plików zgodnie z manifestem.
- Weryfikować digest każdego pliku i całego bundle, kolejność chunków, brak duplikatów, offsety, rozmiar końcowy i manifest przed atomowym zatwierdzeniem stagingu.
- Transfer zapisywać do ograniczonego katalogu tymczasowego; nie rozpakowywać ścieżek bez ponownej walidacji. Nie uruchamiać żadnych plików podczas transferu.
- Resume transferu dopiero po ponownym mTLS i ponownej weryfikacji tożsamości, manifestu oraz już zapisanych chunków; domyślnie restart bezpieczniejszy niż wznowienie.

### 4. Kontrakt maszyny stanów sesji (przed implementacją transportu)

Maszyna stanów ma być pojedynczym właścicielem lifecycle sesji; nie może opierać się na samym `MessageType` ani na tym, że caller „obiecuje”, iż pairing przeszedł. Przejście do stanu uwierzytelnionego może nastąpić wyłącznie po bezpośrednim, pozytywnym wyniku `CompanionPairingAuthority.verify` oraz po związaniu `sessionId`, `clientId`, `companionId`, wersji protokołu i tożsamości TLS.

| Stan | Dozwolone następne zdarzenia | Warunki / efekt |
|---|---|---|
| `NEW` | `PAIRING_STARTED`, `CLOSE` | Utworzenie lokalnego kontekstu; brak danych sieciowych zaufanych |
| `PAIRING_PENDING` | `PAIRING_ACCEPTED`, `PAIRING_REJECTED`, `TIMEOUT`, `CLOSE` | Challenge krótkotrwały; błędny proof nie może zużyć challenge ani przełączyć sesji na authenticated |
| `AUTHENTICATED` | `TRANSFER_BEGIN`, `PING`, `CLOSE`, `TIMEOUT` | Wymagane zweryfikowane TLS peer identity + zaakceptowany proof; przypisane klucze sesyjne |
| `TRANSFERRING` | `CHUNK_ACCEPTED`, `TRANSFER_COMPLETE`, `TRANSFER_ABORTED`, `PING`, `CLOSE`, `TIMEOUT` | Tylko jeden aktywny transfer; ścisła kolejność/offset; każdy chunk <=1 MiB; bundle <=40 MiB i <=3000 plików |
| `TRANSFER_VERIFIED` | `CLOSE` | Manifest, wszystkie pliki i digest całego bundle zweryfikowane; dane nadal tylko w stagingu, bez wykonania |
| `CLOSED` | brak | Terminalny; sockety zamknięte, odbiorniki unieważnione, klucze sesyjne wyzerowane best-effort |
| `FAILED` | brak | Terminalny; brak automatycznego wznowienia zaufania lub wykonania |

Wymagania implementacyjne:
- `CLOSE`, timeout, błąd MAC/TLS, niezgodność sesji/tożsamości, nieoczekiwany typ wiadomości, zła kolejność lub przekroczenie limitu kończą sesję; terminalny stan nie przyjmuje już ramek.
- `PING` nie przedłuża bez ograniczeń maksymalnego czasu życia sesji. Osobne limity: handshake, idle, transfer i hard session lifetime.
- `TRANSFER_COMPLETE` nie jest dowodem sukcesu sam z siebie. Wymaga dokładnego rozmiaru, kompletnego indeksu chunków, manifestu, digestów plików i digestu bundle.
- `TRANSFER_VERIFIED` oznacza tylko bezpiecznie odebrane dane. Nie oznacza zgody na uruchomienie, sandboxa ani `PROJECT_TESTS=PASS`.
- Wznowienie połączenia tworzy nowy kontekst uwierzytelnienia; nie wolno przywracać starych kluczy/odbiorników na podstawie samego `sessionId`.

### 5. Sesja, limity i logowanie

- Ustalić osobne timeouty handshake, bezczynności, transferu i całej sesji; limity równoległych sesji, rozmiaru bufora i liczby prób pairing.
- Zamykać socket, zerować kopie kluczy w pamięci tam, gdzie to możliwe, i unieważniać odbiorniki po zamknięciu/wygaśnięciu.
- Logować identyfikatory korelacyjne, kategorie błędów, liczniki i czasy; nigdy PSK, klucze prywatne, tokeny, pełne payloady ani zawartość bundle.
- Discovery może wskazywać host/port, ale nigdy nie jest źródłem zaufania. Każdy znaleziony endpoint musi przejść walidację certyfikatu i tożsamości.

## Bramka bezpieczeństwa przed pierwszym transferem

1. Działający Companion desktopowy z podpisaną dystrybucją i aktualizacjami.
2. TLS/mTLS z testami negatywnymi: certyfikat obcy, wygasły, zły SAN, zły pin, brak certyfikatu klienta, downgrade i MITM.
3. Bezpieczny pairing, rotacja i unieważnianie kluczy.
4. Testy ramek: replay, zmiana kierunku, zmiana długości, duplikaty, kolejność chunków, zamknięcie i wygaśnięcie sesji.
5. Transfer stagingowy: limity, digest, atomowe zatwierdzenie, brak path traversal i brak uruchamiania plików.
6. Rzeczywisty OS-level sandbox executora z egzekwowanym CPU/RAM/czasem i siecią wyłączoną; testy z próbami ucieczki i potwierdzenie polityk przez system operacyjny.
7. Dopiero po przejściu wszystkich bramek można rozważyć podłączenie `PROJECT_TESTS` i zaufania receipt do rollbacku.

## Implementacja 0.6.30 — terminalność odbiornika ramek

`CompanionFrameProtocol.Receiver` egzekwuje teraz lokalną terminalność:
- poprawnie uwierzytelniona ramka `CLOSE` zostaje zaakceptowana, po czym odbiornik przechodzi w stan zamknięty;
- każda kolejna ramka otrzymuje `SESSION_CLOSED`, niezależnie od numeru sekwencji;
- `close()` pozwala właścicielowi transportu unieważnić odbiornik przy timeout, błędzie transportu lub zamykaniu sesji;
- zamknięcie zeruje best-effort prywatną kopię klucza odbiornika w pamięci procesu;
- uwierzytelniona ramka na `Long.MAX_VALUE` kończy odbiornik kodem `SEQUENCE_EXHAUSTED`, ponieważ nie istnieje poprawny następny numer;
- ramka `CLOSE` z błędnym MAC nie zamyka odbiornika, więc nieuwierzytelniony pakiet nie może wymusić DoS.

Ta poprawka dotyczy wyłącznie odbiornika ramek. Nie jest pełną maszyną stanów sesji: nadal nie ma wiarygodnego adaptera TLS, który mógłby wystawić niepodrabialny dowód tożsamości peer i bezpiecznie dopuścić sesję do stanu `AUTHENTICATED`. Nie dodano więc API, które ufałoby samemu `accepted=true` lub deklaracji wywołującego.

## Świadome ograniczenia

W 0.6.30 nie dodano socketów ani TLS, nie generuje się certyfikatów, nie ma desktop Companion i nie istnieje sandbox. Pełna maszyna stanów pozostaje na tym etapie kontraktem projektowym; wdrożono jedynie terminalność odbiornika ramek, nie działający transport. `PROJECT_TESTS` pozostaje `NOT_RUN`. Aktualny protokół jest kontraktem i zestawem walidatorów, a nie gotowym zdalnym executorem.


## Implementacja 0.6.31 — monotoniczna polityka timeoutów

Dodano `CompanionSessionTimeoutPolicy` jako niezależny komponent czasu dla przyszłego właściciela sesji:
- osobny limit handshake, idle timeout, timeout pojedynczego transferu i twardy maksymalny czas życia sesji;
- źródło czasu domyślnie `System.nanoTime()`, bez zależności od zegara ściennego;
- `recordAuthenticatedActivity()` może odświeżyć wyłącznie idle timeout i jest ignorowane przed zakończeniem fazy handshake; ponowne `markHandshakeFinished()` jest idempotentne; nie przesuwa `createdAt`, więc PING nie przedłuża hard lifetime;
- transfer ma pojedynczy slot i własny deadline; nie może się rozpocząć przed zakończeniem fazy handshake;
- granice timeoutów są inkluzywne (`elapsed >= limit`), a arytmetyka różnicowa obsługuje zawinięcie `nanoTime` przy limitach krótszych niż 2^63 ns.

**Granica zaufania:** `markHandshakeFinished()` jest wyłącznie zmianą fazy zegara i nie potwierdza poprawności parowania ani TLS. Klasa nie uwierzytelnia, nie przyjmuje decyzji o stanie `AUTHENTICATED`, nie zamyka socketu/odbiornika i nie jest jeszcze zintegrowana z transportem. Integrator musi wywoływać `recordAuthenticatedActivity()` wyłącznie po pozytywnej weryfikacji ramki. Nie wolno traktować tej klasy jako gotowej maszyny stanów ani bramki bezpieczeństwa.

## Korekta 0.6.32 — atomowe próbkowanie czasu

W `CompanionSessionTimeoutPolicy` każda operacja zmieniająca stan pobiera teraz jedną próbkę zegara monotonicznego. Ta sama wartość służy do oceny timeoutu oraz zapisu `lastActivityAtNanos` / `transferStartedAtNanos`. Eliminuje to rozjazd czasowy, w którym wcześniejszy odczyt mógł jeszcze wskazywać brak timeoutu, a drugi odczyt — wykonany po deadline — odświeżałby znacznik aktywności. Test regresyjny używa deterministycznego zegara zwracającego różne wartości w kolejnych odczytach. To nadal wyłącznie polityka czasu; nie jest dowodem uwierzytelnienia ani implementacją transportu.


## Korekta 0.6.33 — współbieżność i własność klucza odbiornika

`CompanionFrameProtocol.Receiver.verify()` jest metodą synchronizowaną. Test z dwoma równoległymi wywołaniami dla tej samej ramki potwierdza, że aktualizacja sekwencji jest atomowa względem replay: dokładnie jedno wywołanie może zaakceptować numer oczekiwany, a drugie widzi już następny numer i zwraca `SEQUENCE_MISMATCH`. Odbiornik kopiuje tablicę klucza w konstruktorze; test potwierdza, że późniejsze wyzerowanie tablicy należącej do wywołującego nie zmienia klucza używanego przez odbiornik.

To utwardzenie dotyczy lokalnej synchronizacji i własności pamięci. Nie zapewnia poufności, nie chroni przed kompromitacją procesu, nie zastępuje TLS/mTLS ani bezpiecznego provisionowania kluczy i nie tworzy transportu sieciowego.


## Korekta 0.6.34 — kanoniczna reprezentacja payloadu na granicy odbiornika

Walidacja kanonicznego Base64URL nie może zależeć od tego, czy obiekt `Frame` został wcześniej utworzony przez `CompanionFrameWireCodec`. Publiczna metoda `Receiver.verify()` ponownie koduje zdekodowany payload w formacie Base64URL bez paddingu i porównuje go z wejściowym polem. Ramka o alternatywnej reprezentacji tych samych bajtów otrzymuje `NON_CANONICAL_BASE64`; odrzucenie nie zużywa numeru sekwencyjnego. Kodek zachowuje własną kontrolę kanoniczności jako niezależną warstwę walidacji.

To ogranicza niejednoznaczność reprezentacji na granicy odbiornika. Nie zastępuje HMAC, TLS/mTLS ani walidacji tożsamości peer.


## Korekta 0.6.35 — zegar TTL należy do authority parowania

Publiczne metody `begin`, `verify` i `pendingCount` nie przyjmują już parametru `now`. Produkcyjny konstruktor wybiera monotoniczne źródło czasu, a wszystkie operacje danej instancji pobierają czas wewnętrznie. Wstrzykiwanie zegara pozostaje wewnętrznym seamem modułu testowego. Dzięki temu kod wywołujący nie może przypadkowo mieszać zegara ściennego z monotonicznym ani cofać czasu pomiędzy operacjami. To utwardzenie API TTL, nie dowód tożsamości peer i nie implementacja transportu.


## Uzupełnienie 0.6.36 — receipt authority-issued

`Verification.accepted` jest wyłącznie wynikiem diagnostycznym i nie może autoryzować utworzenia sesji. Po poprawnym proof `CompanionPairingAuthority` wydaje receipt, którego dokładna tożsamość obiektu jest przechowywana w ograniczonym rejestrze authority. Właściciel sesji musi odebrać receipt przez tę samą instancję authority; operacja usuwa go z rejestru i może się udać tylko raz. Caller-created obiekty implementujące marker interface, receipt z innej authority i powtórne użycie są odrzucane. Rejestr ma limit 32 wpisów.

To jest zabezpieczenie kontraktu API w obrębie zaufanego procesu, a nie granica przeciw złośliwemu kodowi wewnątrz procesu. Nie zastępuje TLS/mTLS, weryfikacji certyfikatu peer, provisionowania kluczy ani OS sandboxa.


## Implementacja 0.6.38 — platformowy kontekst TLS/mTLS (fundament, nie transport)

`CompanionTlsContext` używa platformowych fabryk `KeyManagerFactory` i `TrustManagerFactory` oraz `SSLContext("TLS")`. Wymaga prywatnego klucza z łańcuchem certyfikatów i jawnego trust store. Klient tworzy socket przez `SSLSocketFactory.createSocket(host, port)`, ogranicza protokoły do TLS 1.2/1.3 i ustawia `endpointIdentificationAlgorithm=HTTPS` przed rozpoczęciem handshake. Serwerowy `SSLServerSocket` wymaga certyfikatu klienta (`needClientAuth=true`). Brak TLS 1.2, tożsamości klucza lub certyfikatów zaufania jest odrzucany.

**Nie uznajemy jeszcze mTLS za gotowe.** Nie ma provisioningu certyfikatów, SPKI pinning, rotacji/unieważniania, integracyjnego handshake z certyfikatami obu stron, listenera/transportu ani powiązania certyfikatu peer z `CompanionSessionOwner`. Trust store i certyfikaty muszą zostać dostarczone przez przyszły bezpieczny provisioning. Nie wolno przesyłać bundle ani traktować `PROJECT_TESTS` jako PASS.


## Implementacja 0.6.39 — testy integracyjne handshake TLS/mTLS na loopback

Dodano testowe certyfikaty X.509 (root CA, zaufany serwer, zaufany klient, alternatywny błędny SAN i niezaufane certyfikaty) wyłącznie w zasobach testowych. Testy używają platformowego `SSLContext`, `SSLServerSocket` i `SSLSocket` oraz wykonują rzeczywisty handshake. Pozytywnie zweryfikowano mTLS z zaufanymi certyfikatami i osobno TLS 1.2. Negatywnie zweryfikowano brak certyfikatu klienta, łańcuch klienta od niezaufanego CA, niezgodny SAN serwera oraz łańcuch serwera od niezaufanego CA. Zestaw 61/61 testów przechodzi, a 10 powtórzeń daje 610/610.

To nadal test JVM na loopback. Nie potwierdza działania na Androidzie, nie dostarcza provisioningu, pinningu SPKI, rotacji/unieważniania, listenera sieciowego ani powiązania certyfikatu z `CompanionSessionOwner`. Klucze prywatne w zasobach testowych są publicznymi fixture testowymi i nigdy nie mogą być używane produkcyjnie. Transfer bundle pozostaje zablokowany, a `PROJECT_TESTS` pozostaje `NOT RUN`.
