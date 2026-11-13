# AI MASTER PRO — przegląd integracji Companion pkt 4 (0.6.56-SOURCE)

Data: 5 listopada 2026  
Baza: `AI_MASTER_PRO_0_6_55_PKT4_DRAFT_HARDENING_SOURCE.zip`  
Status: **AUDYT STATYCZNY + KONTRAKT INTEGRACJI; bez zmian produkcyjnego kodu i bez APK**.

## 1. Decyzja

Nie integrować jeszcze `CompanionConnectionRegistry` do `app/src`. Szkic rejestru rozwiązuje własność uchwytów i wyścig `register/revoke` na poziomie czystego JVM, ale nie zamyka przepływu od rozpoczęcia handshake do stanu terminalnego `CompanionSessionOwner`. Integracja wymaga wspólnego kontraktu trzech elementów: `CompanionPinnedTlsProfile`, `CompanionSessionOwner` i `CompanionProvisioningStore`.

Punkt 6 (transport i transfer bundle) pozostaje zablokowany. Ten dokument nie jest zgodą na otwieranie portu, uruchamianie transferu ani executora.

## 2. Potwierdzone fakty z bieżącego kodu

1. `CompanionPinnedTlsProfile.accept()` i `connect()` wykonują handshake wewnątrz `establish()` i dopiero potem zwracają `CompanionTlsConnection`. Nie istnieje dziś parametr pozwalający przekazać ticket rejestru przed handshake.
2. `CompanionTlsConnection` udostępnia `SSLSocket` bezpośrednio. Profil nie rejestruje zaakceptowanych/utworzonych socketów jako własności sesji; śledzi tylko serwerowe `SSLServerSocket` i nieodebrane receipt-y.
3. `CompanionSessionOwner.activate(receipt)` wiąże receipt z sesją parowania, rolą i peer ID, po czym ustawia `ACTIVE`. Nie przyjmuje ani nie przechowuje socketu/uchwytu.
4. `CompanionSessionOwner` wywołuje `trustValidator` z metod synchronizowanych. Ścieżka `createSessionOwner()` wiąże walidator z odczytem zaszyfrowanego magazynu `CompanionProvisioningStore`.
5. `CompanionProvisioningStore.revokePeer()` i `wipeAllTrust()` są synchronizowane. `revokePeer()` zapisuje zmieniony rekord przez `commit()`; `wipeAllTrust()` najpierw zatwierdza usunięcie wpisu preferencji, a potem usuwa alias AES z Android Keystore.
6. `CompanionPinnedTlsProfile.accept()` ustawia `server.soTimeout` na współdzielonym `SSLServerSocket` przed `accept()`. Przy równoległych wywołaniach `accept()` na tym samym listenerze wartość timeoutu jest współdzielona, więc nie wolno traktować jej jako niezależnego timeoutu per klient.
7. Receipt-y TLS są przechowywane w `issued` do czasu `consume()`. Jeżeli caller porzuci `CompanionTlsConnection` bez przekazania receipt-u do ownera, wpis pozostaje do limitu `MAX_RECEIPTS`; obecnie brak jawnego anulowania receipt-u i automatycznego usuwania po zamknięciu socketu.

## 3. Nowe ryzyka integracyjne wykryte w audycie

### R1 — ticket pobrany po handshake nie zabezpiecza wyścigu revocation

Ticket musi być pobrany przed `accept/connect` i przed rozpoczęciem handshake. Po handshake i walidacji certyfikatu ticket jest sprawdzany podczas rejestracji. W przeciwnym razie handshake rozpoczęty przed revocation mógłby pobrać już nową epokę i zostać błędnie uznany za świeży.

**Wymaganie:** API profilu musi otrzymać ticket (lub neutralny interfejs `ConnectionAdmission`) przed operacją sieciową i przenieść go bez zmian do wyniku handshake. Nie wolno samodzielnie odtwarzać ticketu po handshake.

### R2 — zamknięcie socketu nie jest zmianą stanu ownera

Samo `SSLSocket.close()` odblokowuje I/O, ale nie ustawia `CompanionSessionOwner.State.FAILED`, nie czyści jego receivera/identyfikacji i nie daje jednoznacznego wyniku diagnostycznego.

**Wymaganie:** owner musi mieć wewnętrzną operację revocation, która atomowo ustawia stan terminalny i odłącza zasoby logiczne. Operacja nie może sama wywoływać `socket.close()` wewnątrz monitora ownera. Zamknięcie fizycznego socketu odbywa się po wyjściu z monitora.

### R3 — niebezpieczny lock ordering przy listenerze magazynu

Nie wolno uruchamiać callbacku revocation wewnątrz `@Synchronized` `CompanionProvisioningStore`. Owner może w tym samym czasie wykonywać `trustValidator` i czekać na monitor store. Callback store → owner podczas trzymania monitora tworzyłby cykl blokad.

**Wymaganie:** metody modyfikujące trust muszą zostać rozdzielone na fazę pod monitorem (walidacja + trwały commit + przygotowanie zdarzenia) oraz fazę poza monitorem (publikacja zdarzenia i zamykanie zasobów). Listener nie może być wywoływany pod monitorem store ani registry.

### R4 — błąd usuwania aliasu po udanym wipe nie może zachować aktywnego połączenia

Jeżeli `prefs.edit().remove(PINS_KEY).commit()` zwróci sukces, zaufanie zostało trwale usunięte. Późniejszy błąd `deleteEntry(METADATA_ALIAS)` nie może anulować unieważnienia aktywnych sesji.

**Wymaganie:** po udanym commit usunięcia pinów zdarzenie globalnego revocation musi zostać opublikowane niezależnie od wyniku usuwania aliasu. Ewentualny wyjątek aliasu wolno zgłosić dopiero po zakończeniu unieważniania aktywnych połączeń.

### R5 — współdzielony timeout listenera

`SSLServerSocket.soTimeout` jest stanem listenera, nie indywidualnego połączenia. Równoległe `accept()` z różnymi timeoutami mogły nadpisywać ustawienia.

**Status 0.6.57-SOURCE:** poprawione w `CompanionPinnedTlsProfile`. Ustawienie `soTimeout` i `accept()` są serializowane monitorem konkretnego listenera; handshake pozostaje poza monitorem. `closeServer()` nie czeka na monitor accept i może przerwać oczekujące przyjęcie połączenia. Dedykowany harness loopback mTLS potwierdził: pierwszy accept kończy handshake, drugi zachowuje swój krótszy timeout i kończy się `SocketTimeoutException`. Test JUnit dodano, ale Gradle/JUnit pozostaje NOT RUN.

### R6 — porzucone receipt-y TLS

Nieodebrany receipt zajmuje slot `issued` aż do limitu 32. Zamykanie socketu przez caller nie usuwa receipt-u z mapy profilu.

**Wymaganie:** wynik połączenia powinien mieć jawne, idempotentne anulowanie/zużycie receipt-u w przypadku odrzucenia aktywacji, porzucenia połączenia lub błędu rejestracji. Anulowanie musi być ograniczone do receipt-u wydanego przez dany profil i nie może umożliwiać fałszowania tożsamości.

## 4. Kontrakt integracji — kolejność bezpieczna

1. Caller zna oczekiwany peer ID wynikający z kontekstu parowania i pobiera `Ticket` z registry **przed** `accept/connect`.
2. Profil TLS wykonuje połączenie i handshake. W przypadku błędu zamyka socket; ticket zostaje bez możliwości ponownego użycia po zmianie epoki.
3. Profil weryfikuje PKIX, SAN/hostname (dla klienta), wersję TLS i SPKI przypisany do oczekiwanego peer ID. Receipt pozostaje jednorazowy.
4. Owner konsumuje receipt TLS i sprawdza: pairing session ID, role, oczekiwany peer ID oraz bieżący pin w durable trust store.
5. Dopiero po poprawnej aktywacji owner przekazuje do registry dokładnie ten sam ticket i dokładny handle opakowujący socket. Rejestracja odrzuca ticket, jeśli od czasu handshake nastąpiło peer/global revocation.
6. Owner przechowuje referencję do dokładnego handle i przy normalnym zamknięciu zwalnia ją z registry. Zwolnienie nie może zamykać socketu drugi raz ani zwalniać uchwytu innego peera.
7. `revokePeer`: trwały commit usunięcia pinów → registry zwiększa epokę i odłącza handle → poza blokadami owner przechodzi do `FAILED` → sockety są zamykane poza wszystkimi monitorami.
8. `wipeAllTrust`: trwały commit usunięcia całej mapy i próba usunięcia aliasu w jednej sekcji store → poza monitorem publikacja globalnej epoki/odłączenie handle → owner-y przechodzą do `FAILED` → sockety są zamykane → dopiero potem zgłaszany jest ewentualny błąd aliasu lub listenera. Sekcja krytyczna zapobiega utworzeniu nowego wpisu szyfrowanego kluczem, który jest właśnie usuwany.
9. Rejestracja rozpoczęta przed revocation, ale kończąca się po nim, musi być odrzucona przez stary ticket. Rejestracja kończąca się przed revocation musi zostać odnaleziona i zamknięta przez revocation.

## 5. Reguły blokad i callbacków

- Nie wywoływać `Closeable.close()` pod blokadą registry, ownera, profilu TLS ani store.
- Nie wywoływać listenera pod blokadą store.
- Nie wywoływać kodu zewnętrznego/callbacku pod blokadą registry.
- Ustalić jeden kierunek zależności: store publikuje zdarzenie → coordinator/registry odłącza zasoby → owner-y oznaczają stan terminalny → uchwyty są zamykane. Żaden z tych kroków nie może ponownie wejść w store, trzymając blokadę innego komponentu.
- Zamknięcie jest best-effort dla każdego uchwytu, ale błąd jednego socketu nie może przerwać zamykania pozostałych.
- Zdarzenia muszą być idempotentne. Powtórzone revocation nie może przywrócić połączenia ani zmienić `FAILED` na `CLOSED`/`ACTIVE`.

## 6. Wymagane testy przed zamknięciem pkt 4

### Registry/core JVM
- stare ticket-y po peer i global revoke;
- wyścig handshake-ticket / revoke / register w obu porządkach;
- ten sam obiekt handle, obiekty równe przez `equals`, cross-peer registration;
- `release` kontra `revoke`, podwójne close, close rzucający wyjątek;
- owner po revocation jest `FAILED`, receiver odrzucony, I/O socketu odblokowane;
- wykrywanie deadlocku w równoległym `trustValidator` i `revokePeer`.

### TLS loopback
- aktywne połączenie server/client zamknięte bez kolejnego wywołania API ownera;
- handshake rozpoczęty przed revocation, zakończony po revocation — nie może aktywować sesji;
- błędny pin, SAN, peer ID i pairing session ID nie rejestrują socketu;
- receipt porzucony/anulowany zwalnia slot `issued`;
- dwa równoległe `accept()` nie naruszają timeoutów ani własności listenera — poprawka 0.6.57; harness loopback PASS, JUnit NOT RUN.

### Store/Android Keystore
- `revokePeer` commit sukces → aktywne połączenia zamknięte;
- commit niepowodzenie → brak fałszywego revocation;
- `wipeAllTrust`: commit sukces + alias delete sukces;
- `wipeAllTrust`: commit sukces + alias delete wyjątek, ale sesje i sockety i tak unieważnione;
- awaria odszyfrowania magazynu nie może przypadkowo wywołać globalnego wipe ani otworzyć dostępu.

## 7. Zakres zmian do przyszłej integracji

- `CompanionConnectionRegistry` — obecny draft może być bazą, po dodaniu jawnego powiązania handle ↔ owner i wyniku revocation.
- `CompanionPinnedTlsProfile` — przeniesienie ticketu przez accept/connect, anulowanie receipt-u, kontrola współdzielonego timeoutu listenera.
- `CompanionSessionOwner` — przyjęcie handle po TLS activation, normal release, wewnętrzne terminalne `failFromRevocation()` bez zamykania socketu pod monitorem.
- `CompanionProvisioningStore` — listener/coordinator wywoływany poza monitorem; osobna obsługa błędu Keystore po commit.
- testy — loopback TLS + fake registry/store + Android Keystore/Robolectric lub test urządzeniowy.

## 8. Status wykonania

| Obszar | Status |
|---|---|
| Statyczny przegląd czterech komponentów | PASS — analiza kodu |
| Zidentyfikowanie dodatkowych ryzyk R1–R6 | PASS — zapisane w tym dokumencie |
| Produkcyjna integracja | NOT IMPLEMENTED |
| Nowe testy JUnit/Gradle | NOT RUN |
| Pełny Gradle / APK / Android Keystore / urządzenie | NOT RUN |
| Transport i transfer bundle | BLOCKED |

**Wniosek:** 0.6.56-SOURCE jest przeglądem kontraktu i listą blokad. Nie zmienia aplikacji, nie zmienia `versionName` ani `versionCode` i nie jest APK.

## 8. Aktualizacja po 0.6.57-SOURCE — R5

W 0.6.57 poprawiono R5 bez integrowania registry i bez uruchamiania transportu. `accept()` serializuje timeout+accept per listener, handshake jest poza blokadą, a `closeServer()` nadal może przerwać oczekujące accept. Kompilacja `kotlinc -Werror` trzech klas TLS oraz dedykowany harness loopback mTLS: PASS. Test JUnit został dodany, ale nie uruchomiony. R1–R4 i R6 pozostają otwarte; pkt 4 nadal OPEN. Szczegóły: `BUILD_STATUS_0.6.57_PL.md`.


## 9. Aktualizacja po 0.6.58–0.6.59-SOURCE — R1/R2 (szkic, nie integracja produkcyjna)

- R1: `CompanionTlsHandshakeGate` pobiera ticket przed `profile.accept/connect`, wykonuje TLS, przekazuje receipt do ownera i używa dokładnie tego samego ticketu przy rejestracji handle. Stary ticket po revocation jest odrzucany.
- R2: `CompanionSessionOwner.invalidateForPeerRevocation()` atomowo przechodzi do terminalnego `FAILED`, zamyka receiver i czyści identyfikację/sekret. Nie zamyka socketu.
- `CompanionRevocationAwareConnection` odróżnia normalne `close()` od `closeForRevocation()`. Registry wywołuje callback revocation po odłączeniu uchwytów i poza własnym lockiem.
- `OwnedConnection` najpierw ustawia ownera w `FAILED`, dopiero potem wykonuje potencjalnie blokujące `socket.close()`. Usuwa receipt i zwalnia własność w `finally`.
- Testy lokalne: owner 19/19, registry draft 16/16 przez shimy/harnessy JVM; nie są to testy JUnit/Gradle. Kompilacja Kotlin core+gate `-Werror`: PASS.
- Gate i registry nadal w `drafts/pkt4`, poza `app/src`; produkcyjny store nie publikuje jeszcze zdarzeń revocation. R1/R2 są przygotowane w kontrakcie, lecz nie uznajemy pkt 4 za zintegrowany. R3/R4/R6 OPEN, pkt 6 BLOCKED.


## 10. Aktualizacja po 0.6.60-SOURCE — R3/R4 (mechanizm przygotowany, binding nadal draft)

- `CompanionProvisioningStore.revokePeer()` wykonuje trwałą zmianę pod monitorem store, zwalnia monitor i dopiero wtedy publikuje zdarzenie. Brak wpisu również publikuje zdarzenie, aby umożliwić ponowienie nieudanego unieważnienia. Jeśli commit się nie powiedzie, zdarzenie nie jest emitowane.
- `wipeAllTrust()` wykonuje commit usunięcia pinów i próbę usunięcia aliasu Keystore w jednej sekcji store (bez callbacków), następnie poza monitorem publikuje globalne revocation. Błąd aliasu nie blokuje publikacji; wyjątek jest zgłaszany dopiero po próbie wszystkich listenerów. Sekcja krytyczna zapobiega wyścigowi z provisionowaniem nowego wpisu przy usuwanym kluczu.
- `CompanionTrustRevocationPublisher` próbuje wywołać wszystkich listenerów i zbiera błędy. `CompanionTrustRevocationException.trustChangeCommitted=true` komunikuje, że trwała zmiana już zaszła, nawet jeśli unieważnianie lub usuwanie aliasu nie zakończyło się w pełni.
- `CompanionTrustRevocationBinding` łączy zdarzenia store z `registry.revoke(peerId)`; pozostaje szkicem poza source set.
- Walidacja: Kotlin store/core/gate z atrapami Android/JSON `kotlinc -Werror` PASS; publisher 2/2 przez lokalny harness PASS. Nie jest to test Android Keystore/Robolectric/Gradle.
- R3/R4 przygotowane w kodzie store, ale brak produkcyjnego bindingu do registry; pkt 4 nadal OPEN. R6 pozostaje OPEN.


## 11. Aktualizacja po 0.6.61-SOURCE — R6 receipt lifecycle

- `IssuedPeer` przechowuje teraz socket oraz monotoniczny czas wydania receipt-u.
- Profil TLS usuwa receipt-y autonomicznie po 30 sekundach przez wspólny daemon scheduler, a także przy wykryciu zamkniętego socketu/nieważnej sesji podczas purge na issuance/consume. Istnieje jawna metoda `discard()`.
- Usunięcie wpisu z mapy odbywa się pod lockiem profilu, ale fizyczne `socket.close()` zawsze poza lockiem. To samo dotyczy `discard()`, purge i zadania scheduler.
- `discard()` zamyka również powiązany socket, więc anulowanie capability nie pozostawia osieroconego połączenia.
- Kopia TLS session ID jest zerowana przy discard, expiry, invalid session oraz po przekazaniu kopii do `PeerIdentity`; owner zeruje swój session ID przy `CLOSED`/`FAILED` i przy nieudanej aktywacji.
- Testy lokalne owner/profile: 21/21 PASS przez JVM harness z rzeczywistymi certyfikatami loopback; `kotlinc -Werror` core/gate PASS. JUnit/Gradle NOT RUN.
- R6 jest zabezpieczone na poziomie profilu, ale wymaga jeszcze testu pełnej integracji gate/registry/store i nie zamyka pkt 4.


## 12. Aktualizacja po 0.6.62-SOURCE — loopback gate/owner/registry

Dodano test integracyjny na rzeczywistych certyfikatach testowych i loopback mTLS. Przepływ gate pobiera ticket, wykonuje handshake, owner konsumuje receipt i aktywuje sesję, a registry przejmuje opakowany handle. Wywołanie `registry.revoke(peerId)` zamyka socket i ustawia ownera w `FAILED` bez kolejnego wywołania API ownera; identity zostaje wyczyszczone. Lokalny harness JVM: 22/22 PASS. Test nie jest JUnit/Gradle i nie obejmuje jeszcze callbacku z Android store ani Android Keystore. Registry/gate/binding pozostają draftem poza source set; pkt 4 nadal OPEN.


## 13. Aktualizacja po 0.6.63-SOURCE — single-use admission ticket

- Registry przechowuje aktywne bilety po tożsamości obiektu; `register()` konsumuje bilet przy pierwszej próbie, również gdy próba zostaje odrzucona. Ponowne użycie biletu do przyjęcia drugiego handle jest fail-closed.
- Limit oczekujących ticketów wynosi 32, zgodnie z limitem receipt-ów TLS; zapobiega nieograniczonemu wzrostowi oczekujących handshake admission.
- `CompanionTlsHandshakeGate.establish()` anuluje bilet w `finally`, więc błąd handshake lub aktywacji ownera nie pozostawia biletu w pamięci.
- Ponowna rejestracja dokładnie tego samego, już posiadanego handle pozostaje no-op/idempotentna.
- Registry draft: 19/19 lokalnych testów shim; gate/core `kotlinc -Werror` PASS; owner/gate loopback 22/22 lokalnym harness. JUnit/Gradle NOT RUN.
- Poprawiono komentarz `wipeAllTrust()`, który niezgodnie z kodem opisywał publikację revocation przed usunięciem aliasu. Rzeczywista kolejność: commit pinów + próba alias delete pod store lock, następnie publish poza monitorem.
- Wszystkie elementy registry/gate/binding nadal pozostają poza Android source set; pkt 4 OPEN.
