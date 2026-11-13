# Projekt 4 — własność połączeń i natychmiastowe zamykanie po unieważnieniu peera

Status: PROJEKT. Brak zmian w kodzie. Zmiana wymaga uruchomienia testów (patrz sekcja 8).

## 1. Stan faktyczny (fakty z kodu, wersja 0.6.52)
- `CompanionPinnedTlsProfile` śledzi WYŁĄCZNIE gniazda serwera (`serverSockets`, `bindServer` / `closeServer`).
- `accept` i `connect` zwracają `CompanionTlsConnection(socket, receipt)`. Od tego momentu profil NIE śledzi połączenia; zamknięcie należy do wywołującego.
- `CompanionSessionOwner` nie zawiera żadnego pola typu gniazdo. Wie o peerze tylko przez `verifiedTlsPeer` i sprawdza zaufanie przy każdej operacji (`failIfPeerTrustInvalid`).
- `CompanionProvisioningStore.revokePeer` i `wipeAllTrust` zmieniają magazyn pinów, ale nie powiadamiają żadnej sesji ani połączenia.

Konsekwencja: po unieważnieniu peera otwarty `SSLSocket` może działać do czasu następnej operacji owner-a. Dokumentacja 0.6.44 tę granicę opisuje wprost; ten projekt ją usuwa.

## 2. Cel
Po `revokePeer(peerId)` (i po `wipeAllTrust`):
1. wszystkie aktywne połączenia tego peera są zamknięte w ramach wywołania `revokePeer`;
2. żadne nowe połączenie dla tego peera nie może zostać przyjęte, nawet jeśli handshake trwał w chwili unieważnienia;
3. zamknięcie nie zależy od tego, czy aplikacja wykona kolejną operację.

## 3. Model
- `CompanionConnectionRegistry` (core): mapa `peerId -> zbiór uchwytów`. Uchwyt = `Closeable` opakowujący `SSLSocket` plus identyfikator sesji.
- Rejestracja: `CompanionSessionOwner.attachConnection(connection)` dopiero po udanym `activate(receipt)`. Owner rejestruje połączenie pod peerId z `verifiedTlsPeer`.
- Generacja: registry trzyma licznik `revocationEpoch` per peer. Rejestracja zapamiętuje epokę z chwili wejścia; jeśli epoka się zmieniła, rejestracja jest odrzucona i połączenie zamykane natychmiast.
- Powiadomienie: `CompanionProvisioningStore` dostaje wstrzykiwany interfejs `RevocationListener { fun onRevoked(peerId: String?) }` (`null` = wszystkie peery, dla `wipeAllTrust`). Wywołanie następuje PO udanym `commit()` zapisu magazynu.
- Zamknięcie: registry usuwa uchwyty ze zbioru pod blokadą, a `close()` wykonuje poza blokadą (unika zakleszczenia z blokadą owner-a).

## 4. Kolejność i współbieżność
- Unieważnienie = (a) zapis magazynu, (b) zwiększenie epoki, (c) oznaczenie owner-ów jako FAILED, (d) zamknięcie gniazd. Krok (b) przed (d) gwarantuje, że rejestracja w trakcie nie przejdzie.
- Handshake w toku: po zakończeniu handshake owner wywołuje `attachConnection`, które sprawdza epokę; odrzucenie zamyka gniazdo.
- Zamknięcie gniazda odblokowuje wątki czytające ramki; receiver kończy się przez wyjątek lub `SESSION_CLOSED`.
- Błąd `close()` nie przerywa pozostałych zamknięć i nie przywraca uchwytu do zbioru.
- Unieważnienie jest idempotentne: podwójne wywołanie nie zmienia stanu.

## 5. Bezpieczeństwo
- Logi i wyjątki nie zawierają PSK, kluczy ani pełnych certyfikatów, tylko identyfikator peera i kod zdarzenia.
- Brak automatycznego ponownego podłączenia po zamknięciu przez unieważnienie.
- Registry nie przechowuje sekretów, tylko uchwyty do gniazd.

## 6. Co NIE jest w tym projekcie
- Transport aplikacyjny i listener sieciowy (zablokowane do zamknięcia bramek punktu 5).
- Strona desktopowa Companion.
- Ochrona przed rollbackiem magazynu.

## 7. Plan testów (NOT RUN — do uruchomienia w domu)
- Zamknięcie otwartego połączenia po `revokePeer` (loopback, fixtury z `companion-tls/`): gniazdo `isClosed == true` po wywołaniu.
- Rejestracja po unieważnieniu odrzucona, połączenie zamknięte.
- Unieważnienie w trakcie handshake: połączenie po handshake nie zostaje zarejestrowane.
- Podwójne `revokePeer` nie zgłasza błędu.
- `close()` rzucający wyjątek nie blokuje zamknięcia pozostałych uchwytów.
- Stres: 1000 iteracji równoległej rejestracji i unieważnienia; żadne połączenie nie pozostaje otwarte po zakończeniu.
- Unieważnienie `wipeAllTrust` zamyka wszystkie połączenia.

## 8. Kryteria zamknięcia pkt 4
- Testy z sekcji 8 przechodzą (wynik Gradle wklejony i zweryfikowany).
- Dokumentacja granic zaktualizowana (usunięte stwierdzenie „zamknięcie przy następnej operacji” dla połączeń już zarejestrowanych).

## 9. Addendum 0.6.55-SOURCE — warunki przed integracją

Szkic rejestru został utwardzony, ale pozostaje poza buildem. Własność uchwytów jest oparta o tożsamość obiektu, a nie `equals/hashCode`; jeden `Closeable` może należeć tylko do jednego peera. Duplikat dla tego samego peera jest idempotentny, konflikt peerów kończy się usunięciem niejednoznacznej własności i zamknięciem uchwytu poza blokadą.

**Ważna korekta przepływu:** `Ticket` musi powstać przed rozpoczęciem handshake TLS. Przepływ `accept/connect` musi przenieść go razem z niezatwierdzonym połączeniem, a `CompanionSessionOwner` może zarejestrować handle dopiero po zweryfikowaniu receipt TLS i zgodności `ticket.peerId` z peer ID wynikającym z roli i tożsamości sesji. Nie wolno pobierać ticketu dopiero po handshake.

**Własność i zwalnianie:** owner powinien przechowywać dokładny handle, a przy normalnym zamknięciu zwalniać go z rejestru. Przy revocation rejestr musi odpiąć handle pod własną blokadą, po czym wykonać `close()` poza nią. Nie wywoływać `socket.close()` pod monitorem `CompanionSessionOwner` ani `CompanionProvisioningStore`.

**Kolejność trwałego revocation:** zmiana magazynu pinów musi zostać zatwierdzona przez `commit()` przed publikacją unieważnienia. Wdrożenie listenera wymaga refaktoryzacji metod `@Synchronized`: callback nie może być uruchamiany wewnątrz monitora store. W `wipeAllTrust()` samo zatwierdzone usunięcie wpisu pinów już oznacza utratę zaufania; jeśli późniejsze usunięcie aliasu Android Keystore nie powiedzie się, listener i tak musi unieważnić aktywne sesje, a wyjątek z usuwania aliasu może zostać zgłoszony dopiero po wykonaniu powiadomienia.

**Odrębne stany ownera:** zamknięcie socketu przez registry nie jest automatycznie równoważne przejściu ownera do `FAILED`. Integracja musi zdefiniować bezpieczny sygnał zamknięcia/odwołania dla ownera bez wywoływania obcego callbacku pod blokadą registry. Testy muszą potwierdzić brak deadlocku, natychmiastowe odblokowanie wątku I/O oraz stan terminalny ownera.

Żadne z powyższych nie jest jeszcze wdrożone w produkcyjnym source set. Zobacz `BUILD_STATUS_0.6.55_PL.md`.
