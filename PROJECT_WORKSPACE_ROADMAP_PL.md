# AI MASTER PRO — kontrakt architektury obszaru roboczego

Ten dokument utrzymuje kolejność wdrożeń i granice bezpieczeństwa, aby następne etapy nie wymagały przebudowy importera.

## Obecny etap: 0.6.91-SOURCE — host-owned ToolExecutionRegistry; Companion pkt 5 nadal OPEN/PARTIAL

Wersje 0.6.18–0.6.19.1 dostarczyły jawne zatwierdzanie, zapis transakcyjny, crash recovery, weryfikację manifestów, rollback i bramkę zaufania. W 0.6.20 dodano wersjonowany kontrakt bundle/request/receipt, allowlistę zadań, politykę zasobów i weryfikację podpisu RSA. W 0.6.21–0.6.24 powstał eksport zweryfikowanego bundle, skan sekretów, challenge-response parowania i uwierzytelnione ramki. W 0.6.25 dodano ograniczony binarny kodek `AIMF`: parser sprawdza rozmiary przed alokacją, wersje, enumy, ścisły UTF-8, kanoniczny Base64URL i brak trailing bytes. W 0.6.26 utwardzono politykę cleartext: HTTP jest domyślnie zablokowane, z wyjątkiem dokładnych endpointów localhost/127.0.0.1 i aliasu emulatora. Audyt 0.6.26 wykazał nieograniczony odczyt odpowiedzi HTTP (`readText()`); w 0.6.27 zastąpiono go ograniczonym strumieniowym readerem 4 MiB z walidacją UTF-8. W 0.6.28 dodano fail-closed walidację endpointów dostawców i projekt TLS/mTLS Companion. W 0.6.29 naprawiono błąd DoS w parowaniu: challenge jest zużywany dopiero po poprawnym uwierzytelnieniu proof, a porównanie HMAC challenge jest stałoczasowe. W 0.6.30 odbiornik ramek egzekwuje terminalne `CLOSE`, odrzuca ramki po zamknięciu, umożliwia jawne unieważnienie i zeruje kopię klucza; wyczerpanie numeracji również kończy odbiornik. W 0.6.31 dodano niezależną politykę timeoutów handshake, idle, transferu i hard lifetime, opartą na zegarze monotonicznym; aktywność nie przedłuża maksymalnego czasu życia sesji. Polityka nie uwierzytelnia peerów i nie jest jeszcze podłączona do transportu. Testy obejmują granice timeoutów, długą fazę handshake, idempotencję znacznika handshake, brak przedłużania hard lifetime oraz zawinięcie nanoTime. Pełna maszyna stanów sesji wymaga przyszłego, rzeczywistego adaptera TLS i nadal nie jest wdrożona. Nadal nie ma poufnego transportu Companion, provisionowania kluczy, desktop executora ani OS-level sandboxa. W 0.6.32 usunięto okno TOCTOU w przejściach polityki timeoutów: sprawdzenie ważności i zapis aktywności korzystają z tej samej próbki zegara monotonicznego. Test regresyjny symuluje zegar, który zmienia wartość pomiędzy odczytami, i potwierdza, że deadline nie może zostać ukryty drugim odczytem. W 0.6.33 rozszerzono audyt odbiornika ramek: test współbieżnego replay potwierdza, że dwie równoległe próby tej samej ramki nie mogą obie przejść, a test własności klucza potwierdza, że odbiornik nie zależy od późniejszych zmian tablicy przekazanej przez wywołującego. Odbiornik jest jawnie opisany jako synchronizowany. W 0.6.35 usunięto możliwość przekazywania dowolnego parametru czasu do publicznych metod authority parowania: produkcyjna instancja sama odczytuje monotoniczny zegar, a seam testowy jest wewnętrzny. Aplikacja nie uruchamia obcego kodu; `PROJECT_TESTS` pozostaje `NOT_RUN`.

`ProjectWorkspaceStore` jest jedynym właścicielem prywatnego katalogu `filesDir/project_workspace`.

- `current/` — rozpakowane, odizolowane źródła projektu.
- `current.manifest` — wersjonowany manifest binarny: nazwa archiwum, czas importu, ścieżki, rozmiary i SHA-256.
- `snapshots/<id>/project/` — kopie poprzednich wersji workspace.
- `snapshots/<id>/manifest.bin` + `snapshot.meta` — manifest i czas wykonania kopii.
- `.staging-*` / `.previous-*` — katalogi transakcyjne; przy starcie/odczycie następuje próba naprawy przerwanego przełączenia.
- `ProjectCodeIndex` — deterministyczny indeks metadanych/ścieżek; nie przechowuje treści.
- `ProjectStructureAnalyzer` — ręczny, lokalny skaner symboli i importów Kotlin/Java z limitami odczytu.
- `readTextFile` — jedyna kontrolowana ścieżka odczytu tekstu: manifest, kanoniczna ścieżka, limit rozmiaru, SHA-256, UTF-8 i filtr sekretów.
- `ProjectPatchEngine` — ograniczony parser unified diff i generator podglądu wynikowej treści; waliduje bazowy SHA-256, ścisły kontekst hunków, ścieżki, duplikaty, limity i sekrety. Sam silnik nie zapisuje ani nie wykonuje zmian.
- `ProjectWorkspaceStore.applyPreview` — osobny, chroniony zapis zatwierdzonego preview: ponowna kontrola integralności, snapshot, staging, nowy manifest i transakcyjne przełączenie prywatnego workspace.
- Centrum dowodzenia udostępnia formularz preview diffu. Po walidacji 0.6.17.1 może uruchomić dwóch recenzentów na różnych darmowych profilach; 0.6.18 pozwala na zapis wyłącznie po ich pozytywnych werdyktach i dwóch jawnych potwierdzeniach użytkownika. Automatyczne generowanie unified diff bezpośrednio z odpowiedzi Mastera pozostaje przyszłym połączeniem.

Importer nie wykonuje plików, skryptów Gradle ani binariów. Nie zapisuje do katalogów instalacji ani repozytorium źródłowego aplikacji.

## Kontrakty, których kolejne etapy muszą przestrzegać

1. Każdy odczyt/zapis musi przechodzić przez `ProjectWorkspaceStore`; UI i agenci nie mogą budować ścieżek bezpośrednio.
2. Ścieżki względne są normalizowane i sprawdzane względem kanonicznego `current/`; żadnych ścieżek absolutnych ani `..`.
3. Manifest SHA-256 jest źródłem prawdy o wersji plików. Przed diffem i przed zastosowaniem patcha należy wykonać kontrolę integralności.
4. Import jest transakcyjny: staging → snapshot poprzedniego workspace → przełączenie katalogu. Nie wolno nadpisywać jedynej kopii.
5. Zmiany agentów muszą być propozycjami patchy, a nie bezpośrednim zapisem odpowiedzi modelu do pliku.
6. Sekrety, konfiguracje lokalne, klucze i katalogi środowiskowe są wyłączone z importu. Nie wolno przekazywać ich do kontekstu modelu.
7. Testy uruchamiane na kodzie użytkownika będą wymagały jawnego sandboxa/procesu z limitami czasu, pamięci i dostępu do sieci. Sam import nie uprawnia do uruchamiania Gradle.
8. Status `APPLIED` w ChangeSetStore nie może być nadawany wyłącznie na podstawie deklaracji agenta; musi odpowiadać rzeczywistemu zapisowi patcha i weryfikacji.

## Kolejność kolejnych wdrożeń

1. **Import + manifest + snapshot + integralność** — wdrożone w 0.6.13.
2. **Indeks metadanych i ograniczony podgląd plików** — wdrożone w 0.6.14. Indeks klasyfikuje ścieżki/rozszerzenia, a wybór kontekstowy dopasowuje nazwy/ścieżki; nie jest jeszcze indeksem AST ani symboli.
3. **Analiza strukturalna symboli i zależności** — wdrożona częściowo w 0.6.15 dla Kotlin/Java. Rozpoznaje podstawowe deklaracje i importy; nie jest pełnym AST ani analizą wywołań.
4. **Generator/podgląd patchy unified diff** — silnik walidacji i preview wdrożony w 0.6.16; bez zapisu.
5. **Peer Review patcha** — 0.6.17 dodaje lokalny audyt ryzyka i agregację werdyktów; 0.6.17.1 spina automatyczne wywołanie dwóch recenzentów przez `TeamRunEngine`, Smart Budget, checkpointy i dziennik wyników. Wymagane są dwa różne, zweryfikowane darmowe profile; brak płatnego fallbacku.
6. **Jawna zgoda użytkownika, snapshot i bezpieczny zapis** — wdrożone w 0.6.18; zapis tylko do prywatnego workspace, stan `APPLIED_UNVERIFIED`. 0.6.18.1 utwardza odzyskiwanie po przerwaniu operacji i czyści osierocone stagingi.
7. Lokalny runner weryfikacji integralności workspace/snapshotu i zgodności zakresu patcha — wdrożony w 0.6.19; loguje osobne wyniki PASS/FAIL/NOT_RUN.
8. Transakcyjne przywracanie zweryfikowanego snapshotu po wykryciu FAIL — wdrożone w 0.6.19.
9. Rzeczywisty executor kompilacji/testów projektu w odrębnym procesie z wymuszonymi limitami CPU/RAM/czasu/sieci — nadal wymagany; do jego wdrożenia nie uruchamiać skryptów Gradle, shell ani binariów z importowanego ZIP-a.
10. Bramka zaufania `ChangeSetStore`, migracja starych deklaracji i walidacja ścieżek — wdrożone w 0.6.19.1.
11. Kontrakt protokołu lokalnego executora — wdrożony w 0.6.20: modele bundle/request/receipt, SHA-256, zamknięta lista tasków, limity i podpisany dowód.
12. Generator zweryfikowanego ZIP bundle — wdrożony w 0.6.21: eksport wyłącznie zweryfikowanego snapshotu, manifest binarny, ponowna kontrola SHA-256 gotowego archiwum i zapis atomowy.
13. Skan treści i nazw plików przed eksportem — wdrożony w 0.6.22; heurystyki wykrywają popularne sekrety i blokują eksport przy wykryciu.
14. Kontrakt dwukierunkowego parowania companion — wdrożony w 0.6.23: nonce, TTL, HMAC-SHA256 po obu stronach, wiązanie klienta/sesji i jednorazowa weryfikacja; bez transportu i bez zapisywania sekretu.
15. Uwierzytelnione ramki companion — wdrożone w 0.6.24: HMAC-SHA256, osobne klucze kierunkowe, digest payloadu, wiązanie kierunku, ścisła sekwencja i ochrona przed replay; bez poufności i transportu.
16. Ograniczony binarny kodek ramek — wdrożony w 0.6.25: wersjonowanie, limity przed alokacją, ścisły UTF-8, allowlista typów/kierunków, kanoniczny Base64URL i odrzucanie trailing bytes.
17. Utwardzenie polityki cleartext — wdrożone w 0.6.26: domyślny zakaz HTTP, jawne wyjątki tylko dla localhost/127.0.0.1 i aliasu emulatora 10.0.2.2; klient odrzuca HTTP do LAN i z kluczem API oraz nie podąża automatycznie za przekierowaniami.
18. **Companion TLS/mTLS** — fundament platformowy 0.6.38 oraz rzeczywiste testy handshake na loopback 0.6.39; provisioning, pinning, rotacja, unieważnianie i integracja sesji nadal są kolejnymi bramkami.
19. Desktop Companion i rzeczywisty OS-level sandbox z wymuszonymi limitami; dopiero po audycie bezpieczne spięcie receipt z `PROJECT_TESTS` i rollbackiem.
20. Eksport Git / CI oraz fundament trwałej pamięci Mastera — etap 0.7.x.


## Aktualna kolejność po wydaniach 0.6.35–0.6.39

Poprzednie listy etapów Companion zostały zastąpione obowiązującym rejestrem „Rejestr kolejności i bramek” poniżej. Nie należy stosować starszych kolejności z wcześniejszych sekcji, jeśli różnią się od rejestru. Jedna cegiełka na wydanie; nie otwierać transferu przed ukończeniem bramek bezpieczeństwa.



## 0.6.56-SOURCE — przegląd integracji pkt 4 (5 listopada 2026)

- Baza: 0.6.55-SOURCE; aplikacja nadal `versionName=0.6.54`, `versionCode=80`.
- Wykonano statyczny przegląd `CompanionPinnedTlsProfile`, `CompanionSessionOwner`, `CompanionProvisioningStore` i draftu registry.
- Nowe ryzyka zapisane w `docs/COMPANION_PKT4_INTEGRATION_REVIEW_0.6.56_PL.md`: ticket przed handshake; terminalny stan ownera oddzielony od zamknięcia socketu; callback revocation poza monitorem store; globalne unieważnienie po commit `wipeAllTrust` nawet przy błędzie Keystore; współdzielony `SSLServerSocket.soTimeout`; czyszczenie porzuconych receipt-ów TLS.
- Produkcyjny kod bez zmian. JUnit/Gradle/APK/Android Keystore/device: NOT RUN.
- Pkt 4 OPEN; pkt 5 PARTIAL; pkt 6 BLOCKED.

### Następna czynność techniczna

Przygotować mały, testowalny kontrakt admission/connection handle, który przenosi ticket od chwili przed handshake do rejestracji po TLS activation; jednocześnie zaprojektować bezpieczny sygnał `FAILED` ownera i lifecycle receipt-u. Dopiero potem integrować listener revocation w store poza jego monitorem. Nie rozpoczynać transferu ani transportu.

## Ograniczenia 0.6.18–0.6.19.1
- Zapis odbywa się wyłącznie w prywatnym workspace aplikacji; nie modyfikuje oryginalnego archiwum ani repozytorium na komputerze użytkownika.
- 0.6.19 sprawdza integralność bieżącego workspace, snapshotu i dokładny zakres zmienionych ścieżek. To nie jest kompilacja ani testy semantyczne projektu.
- `PROJECT_TESTS` pozostaje `NOT_RUN`, ponieważ Android app nie ma jeszcze skonfigurowanego OS-level sandboxa z egzekwowanymi limitami CPU/RAM/czasu/sieci. Nie uruchamiamy nieufnych skryptów Gradle w procesie aplikacji.
- Status `APPLIED` wymaga `testStatus=PASS` oraz `testLevel=PROJECT_TESTS`; sam PASS integralności lub ręczny raport nie wystarczy.
- Przy wykryciu FAIL aplikacja próbuje odtworzyć zweryfikowany snapshot. Jeżeli sam snapshot jest uszkodzony lub zapis rollbacku się nie powiedzie, stan pozostaje jawnie nierozstrzygnięty i wymaga ręcznej interwencji.
- Jeśli aktualizacja rejestru zmian nie powiedzie się po zapisie, UI musi pokazać snapshotId i ostrzeżenie; użytkownik nie powinien ponawiać patcha bez kontroli stanu.

## Ograniczenia 0.6.17.1
- Automatyczna runda wymaga dwóch odrębnych, jawnie przypisanych i zweryfikowanych darmowych/lokalnych profili modelowych dla różnych ról agentów. Jeśli profil jest ten sam, recenzja nie startuje.
- Wynik recenzji pozostaje opinią modelu; nie jest dowodem poprawności i nie uprawnia do zapisu.

## Ograniczenia 0.6.17
- Analizator strukturalny obsługuje Kotlin/Java w ograniczonym zakresie i działa tylko ręcznie z Centrum.
- Nie ma pełnego AST, semantycznego rozwiązywania typów, analizy wywołań ani automatycznego przekazywania kodu agentom.
- Limity analizy: 300 plików, 64 KB na plik, 4 MB łącznie.

- Agenci nie mają jeszcze automatycznego dostępu do odczytu ani zapisu workspace; podgląd i analiza są lokalne oraz ręcznie inicjowane z Centrum.
- Analizator nie odtwarza pełnej semantyki języka ani grafu wywołań; rozpoznaje podstawowe deklaracje i zależności importów.
- 0.6.16 generuje wyłącznie podgląd wynikowej treści. Nie stosuje patchy, nie zapisuje plików, nie uruchamia testów projektu ani nie przywraca snapshotu z UI.
- Formularz patcha jest obecnie ręcznym wejściem kontrolnym. Automatyczne przekazywanie diffu z odpowiedzi Mastera/agentów zostanie spięte po wdrożeniu niezależnego Peer Review.
- Parser obsługuje ograniczony, jednoplikowy unified diff z nagłówkami `---` / `+++` i hunkami `@@`; nie obsługuje binarnych diffów, rename/copy, trybów plików ani rozszerzeń Git.
- Przed przyszłym zapisem wymagane będzie ponowne sprawdzenie integralności, SHA-256 i braku dowiązań symbolicznych/kolizji ścieżek; wynik preview nie jest autoryzacją zapisu.
- Kontrola SHA-256 wykrywa zmienione, brakujące, dodatkowe pliki i dowiązania symboliczne, ale sama niczego nie naprawia.


## 0.6.17 — stan Peer Review patcha
- `PatchPeerReviewEngine` analizuje zaakceptowany preview i wykrywa ryzykowne wzorce (m.in. sekrety, dynamiczne wykonywanie kodu, mostki JavaScript WebView, uprawnienia Androida, zmiany builda i usuwanie krytycznych plików).
- Dla dokładnego diffu tworzy ograniczony prompt recenzencki z SHA-256, ścieżką, operacją, wymaganiem dowodów i ścisłym formatem werdyktu.
- Agregator wymaga co najmniej dwóch różnych identyfikatorów recenzentów; sprzeczne werdykty pozostają konfliktem. Dwa APPROVE oznaczają wyłącznie gotowość do ludzkiej akceptacji, nigdy zgodę na zapis.
- Centrum dowodzenia pokazuje wstępny audyt i umożliwia skopiowanie promptu niezależnej recenzji.
- W stanie 0.6.17 automatyczne wywołanie API nie było jeszcze zintegrowane; zostało dodane w 0.6.17.1. Wyniki są logowane w dziennikach i checkpointach aplikacji.

- **Pamięć Mastera:** w 0.6.17 dodano specyfikację `docs/MASTER_MEMORY_ARCHITECTURE_PL.md` i zinwentaryzowano istniejące magazyny; wdrożenie wspólnego modelu pamięci pozostaje planowane na 0.7.0.


## 0.6.17.1 — automatyczny patch review
- Centrum dowodzenia przekazuje dokładny prompt/diff do dwóch agentów przez istniejący `TeamRunEngine`.
- Recenzenci muszą mieć różne fingerprinty przypisanych profili modelowych i zweryfikowany darmowy/lokalny status.
- Smart Budget wymaga pełnej rezerwacji dla dwóch recenzji; brak budżetu blokuje rundę w całości.
- Wyniki i checkpointy są zapisywane w istniejących dziennikach aplikacji.
- Agregator nie ukrywa negatywnego werdyktu lub BLOCKER tylko dlatego, że brakuje drugiej odpowiedzi.
- W 0.6.17.1 zapis nie był jeszcze dostępny; został dodany w 0.6.18 z obowiązkowym snapshotem i zgodą użytkownika.


## 0.6.18 — zatwierdzanie i zapis workspace
- Po dwóch pozytywnych recenzjach użytkownik wykonuje osobne, jawne zatwierdzenie i potwierdzenie operacji.
- `ProjectWorkspaceStore.applyPreview` ponownie sprawdza integralność i SHA-256, tworzy snapshot, stosuje patch w stagingu, przebudowuje manifest i przełącza katalog transakcyjnie.
- Operacje ADD/MODIFY/DELETE są ograniczone do dozwolonych plików tekstowych; sekrety, chronione ścieżki, NUL, błędne UTF-8, kolizje nazw i nieaktualny base hash blokują zapis.
- `ChangeSetStore` przechowuje snapshotId i status `APPLIED_UNVERIFIED`. Status `APPLIED` wymaga późniejszego PASS testów.
- Wykryto i naprawiono zdublowaną deklarację `symlink` w `ProjectWorkspaceStore`, która uniemożliwiała kompilację Kotlin.


## 0.6.18.1 — crash recovery hardening
- Po poprawnym odzyskaniu `current/` + `current.manifest` usuwane są osierocone `.staging-*` oraz `.patch-staging-*` i ich manifesty.
- Jeżeli aktywna para jest niekompletna, najpierw przywracany jest ostatni dostępny `.previous-*`; sprzątanie stagingu nie może wyprzedzać odzyskiwania.
- Testy regresyjne odzyskiwania i sprzątania stagingu dodane do `ProjectWorkspaceStoreApplyTest`.
- Lokalna kompilacja komponentów Kotlin/JVM PASS; testy trzech klas: 33/33 PASS; pełny Gradle/APK NOT RUN.


## 0.6.19 — workspace verification + rollback
- `ProjectWorkspaceStore.verifySnapshot()` sprawdza manifest, SHA-256, brakujące/zmienione/nadmiarowe pliki i symlinki snapshotu.
- `restoreSnapshot()` najpierw weryfikuje snapshot, kopiuje go do stagingu, ponownie sprawdza kopię i wykonuje transakcyjne przełączenie. Zdrowy bieżący workspace otrzymuje dodatkowy snapshot bezpieczeństwa; uszkodzony workspace nie jest bezrefleksyjnie kopiowany jako zdrowy backup.
- `ProjectTestRunner` porównuje bieżący manifest z snapshotem i sprawdza, czy rzeczywisty zakres zmian zgadza się z ChangeSet. Raportuje osobno `WORKSPACE_INTEGRITY`, `SNAPSHOT_INTEGRITY`, `PATCH_SCOPE` i `PROJECT_TESTS`.
- Gdy integralność lub zakres patcha nie przejdzie kontroli, UI uruchamia rollback do zweryfikowanego snapshotu i aktualizuje rejestr na `ROLLED_BACK`, jeśli przywrócenie się powiedzie.
- `ChangeSetStore` zapisuje poziom weryfikacji. `APPLIED` wymaga `PASS` na poziomie `PROJECT_TESTS`; `WORKSPACE_INTEGRITY` nie może awansować zmiany do `APPLIED`.
- Ograniczenie świadome: brak rzeczywistego sandboxa procesu na Androidzie oznacza, że kodu użytkownika nie wykonujemy. Wszystkie kontrole metadanych mogą przejść, ale wynik całości pozostaje `NOT_RUN`, dopóki testy projektu nie zostaną wykonane przez izolowany executor.


## 0.6.19.1 — execution trust gate hardening
- Zamknięto możliwość zapisania ręcznego `PASS / PROJECT_TESTS` przez publiczne API `ChangeSetStore`.
- Wyniki lokalnych kontroli zapisuje wyłącznie `recordWorkspaceVerification()`; raporty ręczne i zgodności wstecznej mają poziom `LEGACY_UNVERIFIED`.
- Historyczne wpisy `PROJECT_TESTS` z wersji bez executora są degradowane do `NOT_RUN / LEGACY_UNVERIFIED`.
- Po statusie `APPLIED` nie można podmienić dowodu ani snapshotu.
- Runner odrzuca niebezpieczne/powielone ścieżki oraz raportuje wyjątki porównania zakresu jako `FAIL`.
- Następny krok: kontrakt lokalnego executora desktopowego; nie uruchamiać obcego kodu w procesie aplikacji Android.


## 0.6.34 — kanoniczny Base64URL w granicy odbiornika
- Audyt wykazał, że `CompanionFrameWireCodec` odrzucał niekanoniczne Base64URL, ale publiczne `Receiver.verify()` mogło zaakceptować alternatywną reprezentację tych samych bajtów, gdy wywołujący omijał kodek.
- Odbiornik po dekodowaniu ponownie koduje payload bez paddingu i wymaga identyczności z wejściowym tekstem; alternatywna reprezentacja zwraca `NON_CANONICAL_BASE64` bez zużycia numeru sekwencyjnego.
- Test regresyjny używa pary `AA` / `AB`, które dekoder Java może zinterpretować jako ten sam bajt, i potwierdza, że poprawna ramka nadal może zostać zaakceptowana po odrzuceniu niekanonicznej.
- Ukierunkowany zestaw Companion: 34/34 PASS. Nadal nie ma transportu TLS/mTLS, desktop Companion ani OS-level sandboxa.

## 0.6.33 — współbieżność i własność klucza odbiornika Companion
- Potwierdzono testem, że równoległe dostarczenie tej samej ramki do jednego odbiornika kończy się dokładnie jednym `ACCEPTED`; druga próba otrzymuje `SEQUENCE_MISMATCH`.
- Potwierdzono testem, że odbiornik przechowuje niezależną kopię klucza sesji: wyzerowanie tablicy klucza po konstrukcji nie wpływa na poprawną weryfikację ramki.
- Doprecyzowano w dokumentacji kodu, że odbiornik jest synchronizowany i serializuje weryfikację sekwencji.
- Ukierunkowany zestaw Companion: 33/33 PASS. Nie dodano transportu ani nowej granicy zaufania.

## 0.6.32 — spójność próbkowania zegara timeoutów Companion
- Wszystkie przejścia polityki timeoutów pobierają jedną próbkę `nanoTime`.
- Ta sama wartość służy do sprawdzenia deadline i aktualizacji znacznika czasu, eliminując rozjazd między kontrolą a zapisem.
- Dodano test regresyjny z zegarem zmieniającym wartość pomiędzy odczytami.
- Ukierunkowany zestaw Companion: 31/31 PASS; pełny Gradle/Robolectric i urządzenie pozostają NOT RUN.

## 0.6.31 — polityka timeoutów sesji Companion
- Dodano `CompanionSessionTimeoutPolicy` z osobnymi limitami handshake, bezczynności, transferu i hard lifetime.
- Zegar domyślny to `System.nanoTime`; porównanie przez różnicę czasu obsługuje zawijanie licznika monotonicznego dla rozsądnych limitów krótszych niż 2^63 ns.
- `recordAuthenticatedActivity()` odświeża idle timeout, ale nigdy nie przesuwa początku sesji ani twardego deadline.
- Transfer ma jeden aktywny slot i własny limit; nie można rozpocząć transferu przed zakończeniem fazy handshake ani zakończyć go dwukrotnie.
- To wyłącznie polityka czasu. `markHandshakeFinished()` nie jest dowodem uwierzytelnienia; caller ma obowiązek wywołać metody aktywności dopiero po zweryfikowaniu ramek. Klasa nie otwiera socketów, nie weryfikuje TLS i nie kończy samodzielnie sesji/odbiornika.

## 0.6.30 — terminalność odbiornika ramek Companion
- `CompanionFrameProtocol.Receiver` po poprawnie uwierzytelnionej ramce `CLOSE` przechodzi w stan terminalny; wszystkie kolejne ramki są odrzucane jako `SESSION_CLOSED`.
- Dodano jawne `close()` dla unieważnienia przy timeout/błędzie transportu i best-effort zerowanie kopii klucza. Uwierzytelnione wyczerpanie `Long.MAX_VALUE` zamyka odbiornik. Niepoprawny MAC `CLOSE` nie zamyka sesji.
- Nie jest to pełna maszyna stanów ani TLS: nie ma adaptera zdolnego bezpiecznie wystawić tożsamość TLS peer. Nie przyjmujemy samego boolean `accepted` jako dowodu uwierzytelnienia.
- Testy regresyjne weryfikują terminalność, unieważnienie, wyczerpanie sekwencji i odporność na nieautoryzowany CLOSE.

## 0.6.23 — kontrakt parowania companion
- `CompanionPairingAuthority` tworzy krótkotrwałe challenge z losowym nonce 256-bit i identyfikatorem sesji; HMAC wyzwania uwierzytelnia Android przed companionem.
- Dowód companion to HMAC-SHA256 obejmujący wersję protokołu, sesję, klienta, companion, nonce, expiry i HMAC wyzwania. Obie strony uwierzytelniają się wspólnym PSK; weryfikacja jest jednorazowa i odporna na ponowne użycie challenge.
- Sekret 16–64 bajty jest dostarczany poza kanałem transportowym; aplikacja nie zapisuje go ani nie loguje. Nie wdrożono jeszcze bezpiecznego provisionowania.
- 0.6.23 utwardza także skan sekretów (UTF-16 / bounded read) i weryfikację manifestu ZIP (bounded read).
- To nie jest jeszcze transport, szyfrowanie kanału, ECDH, companion desktopowy ani sandbox. `PROJECT_TESTS` pozostaje `NOT_RUN`.


## 0.6.24 — uwierzytelnione ramki companion
- `CompanionFrameProtocol` definiuje zamkniętą listę typów wiadomości: `PAIR_CONFIRM`, `BUNDLE_CHUNK`, `BUNDLE_COMPLETE`, `EXECUTION_RECEIPT`, `PING`, `CLOSE`; nie ma typu dowolnego polecenia shell.
- Dwa odrębne klucze sesji są wyprowadzane przez HMAC-SHA256 z PSK i kontekstu sesji/klienta/companion/nonce/kierunku, z separacją domeny od HMAC parowania; uniemożliwia to odbicie ramki w przeciwnym kierunku. Wywołujący musi najpierw uzyskać pozytywny wynik `CompanionPairingAuthority.verify`.
- Każda ramka wiąże wersję, identyfikator sesji, kierunek, numer sekwencyjny, typ, rozmiar i SHA-256 payloadu w MAC. Odbiorca wymaga dokładnie następnego numeru, odrzuca replay, zmianę treści, błędny Base64 i payload większy niż 1 MiB.
- Ramki zapewniają uwierzytelnienie i integralność, ale NIE poufność. Nie wolno przesyłać nimi sekretów ani traktować ich jako gotowego bezpiecznego transportu sieciowego. TLS lub równoważna, zweryfikowana warstwa szyfrująca oraz bezpieczne provisionowanie klucza pozostają wymagane przed transferem przez sieć.
- Nie dodano socketów, discovery, desktop executora ani OS sandboxa. Kod importowanego projektu nadal nie jest uruchamiany; `PROJECT_TESTS` pozostaje `NOT_RUN`.


## 0.6.25 — ograniczony kodek ramek companion
- `CompanionFrameWireCodec` serializuje ramki do wersjonowanego formatu binarnego `AIMF`; nie otwiera połączeń i nie wykonuje operacji systemowych.
- Parser odrzuca wejście większe niż 1 400 000 bajtów przed parsowaniem, ogranicza długości pól przed alokacją, wymaga poprawnego UTF-8, znanych enumów, poprawnych metadanych i braku dodatkowych bajtów.
- Payload Base64URL musi mieć oczekiwaną długość i kanoniczne kodowanie bez paddingu; niekanoniczne aliasy są odrzucane.
- Kodek nie potwierdza autentyczności: każda zdekodowana ramka nadal musi przejść `CompanionFrameProtocol.Receiver.verify()`.
- Nadal brak poufności, TLS, bezpiecznego provisionowania, transportu sieciowego, desktop executora i OS sandboxa. `PROJECT_TESTS` pozostaje `NOT_RUN`.


## 0.6.26 — utwardzenie polityki cleartext
- Manifest Androida ustawia `usesCleartextTraffic=false` i wskazuje `network_security_config.xml`; domyślna polityka blokuje HTTP.
- Jawne wyjątki sieciowe są ograniczone do `localhost`, `127.0.0.1` i `10.0.2.2` (alias hosta emulatora). Lista w `CleartextEndpointPolicy` jest utrzymywana zgodnie z konfiguracją XML.
- `ProviderClient` odrzuca cleartext do adresów LAN, z kluczem API oraz z danymi uwierzytelniającymi w query; automatyczne podążanie za przekierowaniami jest wyłączone, aby nie dopuścić do niejawnego downgrade/forwardingu nagłówków.
- To celowo wyłącza dotychczasowe niezabezpieczone endpointy Ollama pod adresami LAN; do takiego użycia potrzebny będzie HTTPS z walidacją certyfikatu. Alias emulatora `10.0.2.2` pozostaje dozwolony dla lokalnego testowego Ollama.
- Nie jest to jeszcze TLS/mTLS companion ani cert pinning.


## 0.6.27 — ograniczone odpowiedzi dostawców

- Zastąpiono nieograniczony odczyt `readText()` strumieniowym odczytem o limicie 4 MiB.
- Zbyt duże odpowiedzi kończą się `RESPONSE_TOO_LARGE`; niepoprawne UTF-8 kończy się `INVALID_RESPONSE_ENCODING`.
- Testy graniczne readera: 4/4 PASS; pełny Gradle i APK pozostają NOT RUN.
- Następny bezpieczny krok: audyt i projekt TLS/mTLS dla companion, bez przesyłania poufnych danych przez HMAC-only transport.


## 0.6.28 — walidacja endpointów i projekt bezpieczeństwa Companion

- Dodano `ProviderEndpointPolicy`: adres jest parsowany jako URI/URL przed otwarciem połączenia; akceptowane są wyłącznie HTTP i HTTPS.
- Odrzucane są nieobsługiwane schematy (np. `file:`/`ftp:`), błędne adresy, brak hosta, port 0 i porty powyżej 65535 oraz user-info w URL.
- Reguła cleartext pozostaje fail-closed: tylko `localhost`, `127.0.0.1`, `10.0.2.2`, bez API key i bez danych uwierzytelniających w query (również po percent-decoding). Porównanie hostów jest niezależne od ustawień locale.
- Dodano testy polityki endpointów i dokument architektury TLS/mTLS, provisionowania, transferu bundle i bramek sandboxa.
- Audyt potwierdza, że obecne HMAC Companion nie zapewnia poufności; nie dodano socketów ani TLS. `PROJECT_TESTS` nadal `NOT_RUN`.
- Kotlin/JVM harness polityki endpointów z `-Werror`: 18/18 PASS. Zestaw regresyjny Kotlin/JVM z `-Werror`: 29/29 PASS (pairing, ramki, kodek, executor, cleartext i endpoint policy); reader odpowiedzi dostawcy: 4/4 PASS. XML manifestu i network security config: PASS (parsowanie). Pełny Gradle, testy Android/Robolectric, APK i urządzenie: NOT RUN.


## 0.6.29 — pairing DoS hardening i lifecycle Companion
- `CompanionPairingAuthority.verify()` nie usuwa oczekującego challenge przed sprawdzeniem MAC. Błędny sekret lub zmieniony challenge authenticator nie blokują już prawidłowej próby; challenge jest zużywany wyłącznie po poprawnym proof.
- Porównanie `challengeAuthenticator` wykorzystuje `MessageDigest.isEqual`, tak samo jak MAC proof.
- Dodano regresje na próbę zużycia challenge przez błędny sekret i przez zmieniony authenticator.
- Rozszerzono dokument projektowy o jawne stany sesji, terminalność CLOSE/FAILED, timeouty, bramki tożsamości TLS, reguły transferu i rozdzielenie `TRANSFER_VERIFIED` od uruchomienia kodu.
- Nie wdrożono transportu, TLS, desktop Companion ani sandboxa.

## Rejestr kolejności i bramek — utrzymywać bez przestawiania

**Zasada pracy:** jedna mała, testowalna cegiełka na wydanie; najpierw audyt, potem zmiana, regresje, kontrola dokumentacji i archiwum. Nie budować transportu ani executora na niezweryfikowanym kontrakcie. Każdy status ma rozróżniać PASS / FAIL / NOT RUN / NOT IMPLEMENTED.

### Ścieżka Companion — kolejność obowiązująca
1. [x] Własność zegara TTL parowania — 0.6.35.
2. [x] Niepodrabialny na poziomie API, jednorazowy receipt wydawany przez authority; `accepted` jest diagnostyczne, a receipt jest sprawdzany przez tożsamość obiektu i można go odebrać tylko raz — 0.6.36. To nie jest granica bezpieczeństwa dla złośliwego kodu w tym samym procesie.
3. [x] `CompanionSessionOwner`: jawne stany `NEW`, `PAIRED`, `ACTIVE`, `TRANSFERRING`, `CLOSED`, `FAILED`; konsumuje receipt z przypisanej authority, wyprowadza klucz odbiorczy, zarządza receiverem i timeoutami, a timeout/close prowadzi do stanu terminalnego — 0.6.37. Właściciel sam tworzy politykę timeoutów; zegar testowy jest dostępny wyłącznie przez konstruktor wewnętrzny. `ACTIVE` oznacza zakończone parowanie PSK, nie weryfikację TLS/mTLS.
4. [~] TLS/mTLS: 0.6.38 dodaje platformowy SSLContext; 0.6.39 potwierdza handshake TLS/mTLS na loopback; zrekonstruowane 0.6.40 dodaje SPKI pinning po PKIX; 0.6.41 wiąże jednorazowy dowód TLS peer z konkretnym profilem, mapą peer ID → SPKI i `CompanionSessionOwner`. Nadal brak trwałego provisioningu, rotacji/unieważniania i testów na Androidzie.
5. [~] Provisionowanie i trwałe mapowanie peer ID → SPKI: 0.6.42 dodaje klucz EC P-256 w Android Keystore, nieeksportowalne podpisywanie challenge, AES-256-GCM dla mapy pinów, current/next, jawne promote/cancel i revocation. 0.6.43 dodaje domenowo separowany challenge proof oraz preferowaną ścieżkę tworzenia profilu TLS i `CompanionSessionOwner` bezpośrednio z aktualnej mapy zaufania. 0.6.44 dodaje walidację aktualnego zaufania peer przy aktywacji i przed każdą kolejną operacją sesji; rotacja promote/unieważnienie powoduje fail-closed sesji przy następnym użyciu. Nadal brakuje certyfikatu TLS z SAN wydanego/provisionowanego przez zaufany proces, migracji PSK, odzyskiwania/ponownego parowania, natychmiastowego zamknięcia gniazda TLS (właściciel sesji nie zarządza jeszcze socketem) i testów Android/Keystore na urządzeniu.
6. [ ] Transport Companion: ograniczony transfer bundle, staging, manifest/digest, przerwania, timeouty, replay i reconnect; bez wykonywania w trakcie odbioru.
7. [ ] Desktop Companion i integracyjne testy obu stron.
8. [ ] OS-level sandbox w odrębnym procesie z egzekwowanymi limitami CPU/RAM/czasu/sieci/pliku; dopiero potem realne `PROJECT_TESTS`.

### Ścieżka aplikacji — równolegle, ale bez omijania bramek
9. [ ] Pełny Gradle/Robolectric i diagnoza wszystkich błędów buildu.
10. [ ] Zweryfikowany APK, instalacja/aktualizacja i test na urządzeniu.
11. [ ] Trwała pamięć Mastera: rozdzielenie historii, wiedzy, logów, checkpointów i danych uczących się; retencja, eksport i kontrola użytkownika.
12. [ ] Efektywność agentów: routing, ocena jakości, redukcja niepotrzebnych wywołań i rzeczywiste rozliczanie tokenów.
13. [ ] Automatyzacja patchy i Git/CI: propozycje, review, jawna zgoda, test, rollback i powtarzalne wydania.

**Bieżący punkt:** pozycje 1–3 ukończone. Fundament TLS/mTLS, handshake loopback, SPKI pinning i integracja dowodu peer z `CompanionSessionOwner` są zaimplementowane w 0.6.38–0.6.41. W 0.6.57 poprawiono współdzielony timeout `SSLServerSocket`: `accept()` jest serializowany per listener, handshake pozostaje poza blokadą, a harness loopback mTLS potwierdził izolację timeoutów. JUnit/Gradle nadal NOT RUN. Pkt 4 pozostaje OPEN z powodu R1/R2/R3/R4/R6: admission ticket przed handshake, terminalny stan ownera i socket handle, callback poza lockiem store, częściowy błąd `wipeAllTrust` oraz cleanup receiptów. Pozycja 5 pozostaje częściowa: brak certyfikatu TLS z poprawnym SAN i procesu jego wydania, migracji PSK, recovery/re-pairing i testów Android/Keystore na urządzeniu. Nie otwierać transportu ani transferu bundle przed zamknięciem tych bramek.


## 0.6.38 — platformowy fundament TLS/mTLS (częściowy punkt 4)
- Dodano `CompanionTlsContext` oparty na platformowych `KeyManagerFactory`, `TrustManagerFactory` i `SSLContext`; brak własnego TLS i brak trust-all.
- Wymagany jest wpis klucza prywatnego z łańcuchem certyfikatów oraz jawny, niepusty trust store.
- Klient tworzy socket przez TLS socket factory dla konkretnego hosta i ustawia `HTTPS` endpoint identification przed handshake. Serwer wymaga certyfikatu klienta przez `needClientAuth=true`.
- Dozwolone wyłącznie TLS 1.2/1.3; brak TLS 1.2 oznacza fail-closed.
- Testy Kotlin/JVM `-Werror`: 55/55 PASS, 10 powtórzeń: 550/550 PASS.
- To tylko fundament konfiguracji. Brak certyfikatów, provisioning, SPKI pinning, rotacji/unieważniania, pełnego handshake, transportu i integracji z właścicielem sesji. Pozycja 4 pozostaje w toku; transfer bundle nadal zabroniony.


## 0.6.39 — rzeczywiste testy handshake TLS/mTLS (częściowy punkt 4)
- Dodano wyłącznie testowe certyfikaty CA/serwera/klienta i klucze fixture w `app/src/test/resources/companion-tls/`; nie są przeznaczone do produkcji.
- Potwierdzono pozytywny handshake mTLS z zaufanymi certyfikatami oraz osobny handshake TLS 1.2.
- Testy negatywne: brak certyfikatu klienta, klient z niezaufanym łańcuchem, błędny SAN serwera i serwer z niezaufanym łańcuchem.
- Kotlin/JVM `-Werror`: 61/61 PASS; 10 powtórzeń: 610/610 PASS.
- To nie jest provisioning ani transport produkcyjny. Brak pinningu, rotacji/unieważniania, integracji z `CompanionSessionOwner`, testu na Androidzie, Desktop Companion i sandboxa. `PROJECT_TESTS` pozostaje `NOT RUN`.


## 0.6.40 — SPKI pinning po walidacji PKIX (rekonstrukcja)
- Odtworzono zmianę SPKI pinning na bazie odzyskanych źródeł 0.6.39; oryginalne archiwum 0.6.40 nie udostępniało bajtów.
- Certyfikat peer musi przejść platformową walidację PKIX, a następnie dopasować się do jawnego SHA-256 DER SubjectPublicKeyInfo. Obsługiwany jest zestaw current+next, ale nie mechanizm rotacji.
- Wybrane testy Kotlin/JVM: 15/15 PASS, 7 powtórzeń: 105/105 PASS; nie jest to pełny Gradle/Robolectric.

## 0.6.41 — tożsamość TLS peer związana z CompanionSessionOwner
- `CompanionPinnedTlsProfile` sam tworzy i zachowuje prywatny SSLContext; tylko sockety utworzone/zaakceptowane przez ten profil mogą wygenerować receipt.
- Receipt jest obiektem jednorazowym i wiąże pairing session ID, peer ID, rolę lokalną, SPKI oraz konkretną sesję TLS. Mapa pinów jest przypisana do konkretnego peer ID.
- `CompanionSessionOwner` otrzymuje profil TLS w konstruktorze. Aktywacja bez TLS receipt jest blokowana; obcy profil, obcy receipt i replay są odrzucane.
- Połączenie klienta ma jawny timeout TCP; profil ogranicza liczbę gniazd serwera i nieodebranych receiptów.
- Testy Kotlin/JVM `-Werror`: 29/29 PASS, 5 powtórzeń: 145/145 PASS; rzeczywisty handshake mTLS loopback w testach. Gradle/Robolectric i urządzenie Android: NOT RUN.
- Nadal brak Android Keystore provisioning, rotacji/unieważniania, transportu Companion, Desktop Companion i OS sandboxa. `PROJECT_TESTS` pozostaje `NOT RUN`.


## 0.6.42 — Android Keystore i trwała mapa zaufania Companion (częściowy punkt 5)
- Dodano `CompanionProvisioningStore`: generuje lokalną tożsamość EC P-256 w Android Keystore i udostępnia podpisywanie challenge bez eksportu klucza prywatnego.
- Mapa peer ID → current/next SPKI jest serializowana do app-private preferences i chroniona AES-256-GCM kluczem Android Keystore oraz AAD. Zapis używa synchronicznego `commit()` i zgłasza błąd, jeśli nie został utrwalony.
- Utrata klucza szyfrującego przy istniejących danych kończy się fail-closed i wymaga jawnego odzyskiwania; nie ma cichego wygenerowania nowego klucza i pozornej utraty/zmiany zaufania.
- Rotacja: stage next, promote next, cancel staged; nie można po cichu zastąpić już oczekującego pinu. Revocation usuwa bieżący i oczekujący pin.
- Dodano czyste testy polityki current/next: 5/5 PASS. Wcześniejszy zestaw TLS/owner 29/29 PASS oraz owner 14/14 PASS.
- Ograniczenie: wygenerowany certyfikat Keystore jest lokalnym identyfikatorem klucza, nie certyfikatem TLS z SAN. Nie podłączono jeszcze tego materiału do `CompanionPinnedTlsProfile`/`CompanionSessionOwner`; wymagany jest bezpieczny proces wydania certyfikatu z właściwym SAN i zaufanym łańcuchem. Migracja PSK, recovery/re-pairing i testy na Androidzie pozostają otwarte.
- Próba `./gradlew :app:compileDebugKotlin --no-daemon` zablokowana przed kompilacją: `UnknownHostException: services.gradle.org`; pełny Gradle/Robolectric, APK i urządzenie Android: NOT RUN (brak dostępnego Android SDK i lokalnej dystrybucji Gradle). `PROJECT_TESTS`: NOT RUN.


## 0.6.43 — integracja mapy zaufania z profilem TLS i proof-of-possession (częściowy punkt 5)
- `CompanionIdentityChallenge` wprowadza domenowo separowany komunikat podpisywany przez klucz EC. Weryfikacja używa publicznego klucza certyfikatu i odrzuca pusty/przekraczający limit challenge oraz niepoprawny/podejrzanie duży podpis.
- `CompanionProvisioningStore.signChallenge()` podpisuje teraz wyłącznie domenowo separowany komunikat; podpisów z innych protokołów nie można bezpośrednio użyć jako dowodu Companion.
- `createPinnedTlsProfile()` pobiera aktualną, odszyfrowaną mapę peer ID → current/next SPKI i buduje profil TLS. Pusta mapa blokuje utworzenie profilu.
- `createSessionOwner()` jest preferowaną ścieżką tworzenia: wiąże świeży profil zaufania z nowym `CompanionSessionOwner`, zamiast polegać na ręcznym przekazywaniu potencjalnie nieaktualnej mapy pinów.
- Profile są snapshotami: po rotacji/unieważnieniu należy zamknąć stare profile/sesje i utworzyć nowe. Automatyczne odcinanie już aktywnych połączeń nie jest jeszcze zaimplementowane.
- Test kryptograficzny JVM: 5/5 przypadków PASS. Nowy kod provisioning + profil TLS + owner przeszedł `kotlinc -Werror` z atrapami API Android/JSON; to kontrola składni i typów, nie test Android Keystore.
- Pełny Gradle/Robolectric, test Android Keystore, APK i test urządzeniowy: NOT RUN; środowisko nadal nie ma dostępnej dystrybucji Gradle/Android SDK. `PROJECT_TESTS`: NOT RUN.
- Nadal brakuje certyfikatu TLS z zaufanym łańcuchem i SAN, migracji PSK, recovery/re-pairing i unieważnienia aktywnych sesji. Transport pozostaje zablokowany.


## 0.6.44 — egzekwowanie aktualnego zaufania peer przez właściciela sesji (częściowy punkt 5)
- `CompanionSessionOwner` otrzymuje walidator zaufania peer; preferowana ścieżka `CompanionProvisioningStore.createSessionOwner()` wiąże go z aktualnym, odszyfrowanym magazynem pinów.
- Aktywacja odrzuca receipt, jeżeli pin peer został już unieważniony lub nie jest już akceptowany (`PEER_REVOKED`).
- Przed `verifyIncoming`, `beginTransfer`, `finishTransferAfterValidation` i `pollTimeout` owner ponownie sprawdza, czy dokładny peer ID + SPKI nadal występuje jako current/next. Brak rekordu, błąd odczytu/uwierzytelnienia magazynu lub wyjątek walidatora oznacza fail-closed i stan `FAILED`.
- Promocja rotacji, która usuwa stary pin, jest wykrywana przy następnej operacji sesji. Etapowanie next nie odcina aktualnego current.
- Dodano dwa testy: unieważniony peer nie aktywuje sesji na bazie wcześniej wydanego TLS receipt; aktywna sesja przechodzi do `FAILED` przy następnym użyciu po unieważnieniu.
- TLS/Owner: 31/31 PASS; 5 powtórzeń: 155/155 PASS. Kompilacja wybranych klas core + provisioning store przez `kotlinc -Werror` z atrapami Android/JSON: PASS.
- Ograniczenie: owner nie posiada jeszcze i nie zamyka socketu TLS, więc wykrycie revocation następuje przy następnym wywołaniu API sesji, a nie natychmiast w chwili zmiany magazynu. Transport nadal nie istnieje i pozostaje zablokowany.
- Pełny Gradle/Robolectric, prawdziwy Android Keystore, APK i test urządzeniowy: NOT RUN. `PROJECT_TESTS`: NOT RUN.


## 0.6.55-SOURCE — utwardzenie szkicu pkt 4 (5 listopada 2026)

To jest kamień milowy źródeł, nie nowe wydanie APK. Wersja aplikacji pozostaje 0.6.54 / versionCode 80.

- Utwardzono `drafts/pkt4/CompanionConnectionRegistry.kt`: własność identity-based, pojedynczy właściciel uchwytu, idempotentna rejestracja, walidacja peerId, ochrona przed overflow epoki, zamykanie poza blokadą.
- Dodano 13 przypadków testowych w szkicu, w tym wyścig register/revoke.
- Lokalny `kotlinc -Werror`: PASS; custom harness 13/13 PASS (nie jest to JUnit/Gradle); niezależny smoke: 1000 iteracji wyścigu PASS.
- JUnit przez Gradle, pełny build i testy urządzeniowe: NOT RUN.
- Rejestr pozostaje poza `app/src`; brak integracji z `CompanionSessionOwner` i `CompanionProvisioningStore`.

### Następna bramka pkt 4
1. Właściciel uruchamia `./gradlew testDebugUnitTest` oraz `./gradlew assembleDebug` na Android Studio.
2. Po zielonych wynikach i przeglądzie kontraktu integracji przenosi się szkic do source set.
3. Bilet epoki musi być pobrany przed handshake i sprawdzony względem uwierzytelnionej tożsamości TLS.
4. Revocation musi trwale zatwierdzić zmianę zaufania, opublikować unieważnienie, a sockety zamykać poza blokadami. `wipeAllTrust` musi unieważnić połączenia także wtedy, gdy późniejsze usunięcie aliasu Keystore zgłosi błąd po zatwierdzonym usunięciu pinów.
5. Dopiero testy integracyjne socketów loopback i testy urządzeniowe mogą zamknąć pkt 4. Transport (pkt 6) pozostaje zablokowany.


## 0.6.57-SOURCE — współbieżność accept Companion TLS
- `CompanionPinnedTlsProfile.accept()` serializuje ustawienie `SSLServerSocket.soTimeout` i `accept()` na monitorze listenera. Handshake TLS odbywa się poza monitorem.
- `closeServer()` usuwa listener z rejestru profilu i zamyka go poza monitorem profilu, nie czekając na blokadę accept.
- Dodano JUnit loopback test dwóch równoległych acceptów o różnych timeoutach. Test nie został uruchomiony przez Gradle.
- `kotlinc -Werror` trzech klas TLS: PASS; niezależny harness loopback mTLS: PASS. Gradle/JUnit/Android: NOT RUN.
- Wersja aplikacji nadal 0.6.54 / code 80; brak APK. Pkt 4 OPEN; pkt 5 PARTIAL; pkt 6 BLOCKED.


## Aktualizacja 0.6.58-SOURCE — 2026-11-05
- R1: dodano szkic `CompanionTlsHandshakeGate`; ticket registry jest pobierany przed wywołaniem TLS `accept/connect`, a ten sam ticket jest używany do rejestracji socketu po handshake. Revocation w trakcie handshake powoduje odrzucenie i zamknięcie socketu.
- Profil TLS otrzymał `discard(receipt)` do usuwania nieodebranych dowodów przy odrzuceniu/zwolnieniu połączenia. Wrapper `OwnedConnection` zapewnia idempotentne zamknięcie, cleanup receipt i release.
- `kotlinc -Werror` gate+registry z atrapami typów TLS: PASS; harness JVM biletu: PASS. Test JUnit, Gradle, APK, urządzenie: NOT RUN.
- Gate pozostaje poza `app/src`; R1 przygotowane jako kontrakt, nie uznane za zintegrowane. R2/R3/R4/R6 OPEN; pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.


## Aktualizacja 0.6.59-SOURCE — 2026-11-05
- R2: dodano `CompanionSessionOwner.invalidateForPeerRevocation()` — terminalny `FAILED`, wyczyszczenie receivera i tożsamości bez operacji socket I/O pod monitorem ownera.
- Registry wywołuje `closeForRevocation()` dla uchwytów świadomych revocation, poza własnym lockiem. `OwnedConnection` ustawia ownera w `FAILED` przed zamknięciem socketu; cleanup receipt/release jest w `finally`.
- Gate po handshake konsumuje receipt przez ownera i dopiero wtedy rejestruje opakowany handle z ticketem pobranym przed handshake. Stary ticket powoduje fail-closed i terminalny owner.
- Walidacja lokalna: core+gate `kotlinc -Werror` PASS; owner 19/19 i registry 16/16 przez własne JVM harnessy/shimy (nie JUnit/Gradle).
- R1/R2 przygotowane w szkicu; gate i registry poza source set, brak integracji store. R3/R4/R6 OPEN, pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.


## Aktualizacja 0.6.60-SOURCE — 2026-11-05
- R3: `CompanionProvisioningStore` publikuje zdarzenia po zwolnieniu monitora. Publisher próbuje wszystkich listenerów i agreguje błędy.
- R4: `wipeAllTrust()` commit-uje usunięcie pinów, próbuje usunąć alias Keystore w tej samej sekcji store, następnie publikuje globalne revocation poza monitorem niezależnie od błędu aliasu. Błąd jest raportowany po dispatchu.
- Dodano `CompanionTrustRevocationBinding` w `drafts/pkt4`, łączący store z registry; nie jest jeszcze używany przez aplikację.
- Kotlin compile store/core/gate/binding z atrapami Android/JSON `-Werror`: PASS; publisher harness 2/2 PASS. Gradle/Robolectric/Keystore/device NOT RUN.
- R3/R4 przygotowane; binding i registry poza source set, pkt 4 OPEN. R6 OPEN, pkt 6 BLOCKED.


## Aktualizacja 0.6.61-SOURCE — 2026-11-05
- R6: receipt TLS ma 30-sekundowy TTL oparty na `System.nanoTime()` i autonomiczny cleanup przez pojedynczy daemon scheduler. Purge usuwa expired, closed-socket i invalid-session entries; zwalnia sloty i zamyka powiązane sockety poza lockiem profilu.
- `discard()` usuwa receipt i zamyka socket; profile i owner zerują kopie TLS session ID w ścieżkach zakończenia.
- Testy loopback owner/profile: 21/21 PASS przez lokalny JVM harness; `kotlinc -Werror` core/gate PASS. JUnit/Gradle/APK/Android Keystore/device NOT RUN.
- R1–R6 mają teraz szkice/mechanizmy, ale gate/registry/binding pozostają poza source set i wymagają end-to-end review. Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.


## Aktualizacja 0.6.62-SOURCE — 2026-11-05
- Dodano rzeczywisty test loopback mTLS, który przechodzi przez `CompanionTlsHandshakeGate`, aktywuje ownera i rejestruje opakowany handle.
- Registry revoke bezpośrednio zamyka socket i ustawia owner w `FAILED`; test potwierdza wyczyszczenie identity oraz pusty registry.
- Owner/gate loopback JVM harness: 22/22 PASS; `kotlinc -Werror` core/gate PASS; registry 16/16 i publisher 2/2 PASS. Wszystkie harnessy są lokalne, nie JUnit/Gradle.
- Store→registry binding nadal draft, poza source set. R3/R4 event publisher jest produkcyjny, ale nie jest podłączony do aplikacji. Pkt 4 OPEN, pkt 6 BLOCKED.


## 0.6.64-SOURCE — audyt wyścigów admission/revocation

- W bazie 0.6.63 wykryto błąd kolejności warunków w `CompanionConnectionRegistry.register()`: stale/replayed ticket był analizowany dopiero po konflikcie właściciela, co mogło usunąć istniejący uchwyt innego peera.
- 0.6.64 odrzuca nieaktualny ticket przed jakąkolwiek zmianą istniejącej własności. Nowy, nieprzypisany handle z nieważnym ticketem jest nadal zamykany fail-closed.
- Dodano prunowanie `peerEpoch` po wygaśnięciu wszystkich ticketów i uchwytów danego peera; 500 cykli testowych nie pozostawia osieroconych epok.
- `OwnedConnection.terminate()` gwarantuje próbę zamknięcia socketu, usunięcia receipt-u i zwolnienia registry również po wyjątku przejścia ownera.
- Registry draft: 23/23 PASS w lokalnym harnessie z shimem JUnit; nie jest to JUnit/Gradle. `kotlinc -Werror` dla registry, gate i rzeczywistych klas TLS/owner: PASS.
- Store→registry binding nadal nie jest instalowany w aplikacji. Nie wykonano pełnego Gradle/JUnit/Robolectric/APK/Android Keystore/device. Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.

### Następny audyt

Sprawdzić kolejność commit→publish→registry epoch→socket close na granicy `CompanionProvisioningStore`, bindingu i rejestru; objąć testami ponowne wywołania, callback exception, równoległy `revokePeer`/`wipeAllTrust`, zamykanie bindingu w trakcie publikacji oraz scenariusz aktywacji ownera między commit a dispatch. Nie integrować produkcyjnie bez pełnego zestawu testów Android.

## 0.6.72-SOURCE — Application-scoped Companion Graph

Graph procesowy został przygotowany jako draft: jeden lazy `CompanionProvisioningStore`, jeden `CompanionRuntimeSlot`, ponowne utworzenie pustego runtime po zamknięciu, bez persystencji socketów/ticketów. `CompanionTrustService` ma już seam do wstrzyknięcia wspólnego store. Manifest nie ma custom `Application`; MainActivity ma dwa stare call sites i nie zostały jeszcze przepięte. Graph i runtime nadal poza produkcyjnym source set. Walidacja: `kotlinc -Werror` + graph 3/3 lokalnie; Gradle/JUnit/APK NOT RUN. Następny etap: 0.6.73 Application lifecycle owner i bezpieczna integracja.


## 0.6.73-SOURCE — rzeczywisty właściciel store w Application

Dodano `CompanionApplication` jako klasę Application i zarejestrowano ją w manifeście. Store provisioning jest leniwy, synchronizowany i współdzielony przez dwa UI call sites poprzez `companionTrustService()`. Dwa testy Robolectric sprawdzają singleton store i DI. Testy nie zostały uruchomione: Gradle wrapper nie pobrał Gradle 8.13 z powodu braku DNS do `services.gradle.org`. Runtime/registry/binding nadal pozostają poza produkcyjnym source set. Następny etap: przygotować promocję runtime + binding do produkcyjnego source set z testami, następnie dopiero podłączyć `CompanionRuntimeSlot` do Application i przeprowadzić pełny build.


## 0.6.74-SOURCE — promocja Companion runtime do production source set

- `CompanionConnectionRegistry`, `CompanionTrustRevocationBinding`, `CompanionRuntime` i `CompanionRuntimeSlot` przeniesione do `app/src/main/java`.
- `CompanionApplication` posiada jeden lazy runtime slot; `companionRuntime()` i `companionTrustService()` korzystają z dokładnie tego samego `CompanionProvisioningStore`.
- Dodano JUnit adnotacje do testów runtime/binding, przeniesiono testy registry i dodano Robolectric testy tożsamości runtime/store/listenera.
- `kotlinc -Werror` PASS; lokalny shim runner 35/35 PASS. Gradle/Robolectric/assembleDebug/APK/device NOT RUN.
- TLS handshake gate i transport pozostają draft-only. Brak automatycznego startu sieci i brak odtwarzania sesji po restarcie procesu.
- Następny krok: uruchomić pełny zestaw Gradle na środowisku z dostępnym Gradle 8.13; po pozytywnym wyniku przeprowadzić osobny audyt gate/owner/registry na rzeczywistym source set.

## 0.6.75-SOURCE — produkcyjny TLS admission gate i Runtime shutdown

- `CompanionTlsHandshakeGate` przeniesiony do produkcyjnego source set. Gate pobiera jednorazowy ticket przed handshake, aktywuje ownera na receipt z przypiętego profilu TLS i dopiero potem rejestruje `OwnedConnection`.
- `CompanionRuntime` posiada jedną bramkę TLS związaną z własnym registry; `CompanionApplication` udostępnia ją z tego samego application-scoped runtime.
- Audyt wykrył i naprawił lukę zamykania: trzymana referencja gate mogła wcześniej próbować admission po `Runtime.close()`. Registry ma teraz permanent `shutdown()`, unieważnia outstanding tickets, odłącza i zamyka aktywne handle; Runtime wykonuje shutdown przed odpięciem bindingu.
- Loopback mTLS test sprawdza sukces i wyścig revocation dokładnie pomiędzy handshake/activation a register. 40 błędnych connect nie pozostawia ticketów. Runtime shutdown testuje zamknięcie aktywnych połączeń i odrzucenie starego biletu.
- `kotlinc -Werror` PASS; lokalny shim gate/runtime 11/11 PASS, istniejący registry/binding/runtime 35/35 PASS; Application seam i manifest PASS. Gradle/JUnit/Robolectric/APK/Keystore/device NOT RUN.
- Bramka jest komponentem produkcyjnym, ale żaden listener ani transport nie jest automatycznie uruchamiany. Wersja aplikacji 0.6.54 / code 80 bez zmian.
- Następny krok: audyt i testy store→TLS profile→owner factory→gate; dopiero potem realne testy Android Keystore i kontrolowane przygotowanie transportu.



## 0.6.76-SOURCE — powiązanie profilu TLS i ownera
- Dodano `CompanionProvisionedSession`, który utrzymuje jeden profil TLS i ownera związanego z tą samą instancją profilu.
- `CompanionProvisioningStore.createSessionProvision()` jest preferowanym factory; starszy `createSessionOwner()` jest deprecated.
- Gate akceptuje capability i przed ticketem sprawdza identity binding w każdej ścieżce.
- `kotlinc -Werror` Companion core PASS; lokalny shim-JUnit gate/runtime 9/9 PASS, w tym loopback mTLS i odrzucenie cross-wire. Android store compile, Gradle/JUnit/Robolectric/APK/Keystore/device NOT RUN.
- Następny krok: pełny audyt i testy end-to-end provisioning→gate→OwnedConnection oraz jawny lifecycle profilu; nie uruchamiać listenera/transportu bez oddzielnej bramki.


## 0.6.77-SOURCE — zamykanie profilu TLS i sesji Companion
- `CompanionPinnedTlsProfile` implementuje trwałe `close()`: odcina nowe bind/connect/accept, zamyka listenery, aktywne sockety, handshake in-flight i receipts.
- Gate-owned connection rejestruje close callback; profil zamyka ownera i zwalnia registry. Wyścig profile-close vs wrapper registration jest obsłużony fail-closed.
- `CompanionProvisionedSession.close()` zamyka ownera i dokładnie sparowany profil; Store sprząta profil po nieudanej konstrukcji ownera.
- Walidacja: `kotlinc -Werror` Companion core PASS; store seam Android/JSON PASS; shim-JUnit 16/16 PASS, w tym loopback mTLS. Pełny Gradle/Robolectric/APK/Android Keystore/device NOT RUN.
- Nie uruchamiać transportu. Kolejny krok: audyt rotacji pinów, wielu równoległych provisionów i lifecycle certyfikatu; po nim pełna walidacja na Android Studio właściciela.


## 0.6.78-SOURCE — rotacja SPKI odcina stare sesje
- Stage zachowuje overlap starego i nowego pinu bez zrywania bieżących sesji.
- Promote wykonuje peer-scoped pre-commit revocation fence, utrwala nowy pin i zamyka stare sesje poza monitorem store.
- Błąd fence/zapisu jest fail-closed; brak raportowania sukcesu bez potwierdzonego commit i cleanup.
- Kotlin core `-Werror` PASS; nowe testy pin-set 3/3 w lokalnym shim runnerze. Android store/Gradle/Robolectric/Keystore/device NOT RUN.
- Kolejny krok: audyt integracji rotacji z pełnym Android store oraz kontrola snapshotów profili i obsługi błędów na rzeczywistym Keystore. Nie uruchamiać transportu.


## Aktualizacja 0.6.79-SOURCE — audyt wyścigów rotacji
- Baza: 0.6.78-SOURCE; aplikacja nadal 0.6.54 / versionCode 80.
- Wykryto fail-open po nieudanym trwałym zapisie trust: stare ciphertext pins mogło pozostać i akceptować nowe handshakes po odcięciu istniejących sesji.
- Destrukcyjne operacje z błędem ustawiają process-local latch i próbują usunąć alias AES metadata w Keystore. Udane usunięcie blokuje odszyfrowanie starego ciphertextu po restarcie; błąd usunięcia jest zachowany i nie wolno deklarować trwałej blokady. Jawny wipe resetuje latch po potwierdzonym usunięciu danych.
- `promotePeerRotation()` sprawdza preconditions przed uruchomieniem fence. Testy registry concurrency 33/33 na uruchomienie × 5; 500 iteracji registration-vs-revoke. `kotlinc -Werror` PASS. Android store/Gradle/Robolectric/Keystore/APK/device NOT RUN.
- Pkt 5 nadal OPEN/PARTIAL; pkt 6 transport BLOCKED. Następny krok: dodać wstrzykiwalny persistence/key-deletion seam i failure-injection testy Android/Robolectric, a następnie potwierdzić promote/revoke/wipe na realnym Android Keystore.


## 0.6.80-SOURCE — redundantny trwały fail-closed Companion

- Baza: 0.6.79-SOURCE; aplikacja nadal `versionName=0.6.54`, `versionCode=80`.
- Po nieudanej destrukcyjnej mutacji trust ustawiany jest process-local latch, zapisywany marker w `SharedPreferences`, atomowo publikowany marker plikowy w `noBackupFilesDir`, a dodatkowo podejmowana jest próba usunięcia klucza AES metadata z Android Keystore.
- Odczyt pinów jest blokowany, jeśli dowolny marker jest aktywny albo nie można wiarygodnie odczytać stanu blokady.
- Jawny wipe usuwa pins + marker preferences w jednym commicie, następnie plikowy marker; latch jest czyszczony dopiero po potwierdzeniu obu operacji.
- Walidacja lokalna: `kotlinc -Werror` Companion core + store seam PASS; fault harness 4/4 + regresja shim-JUnit 6/6 PASS. Robolectric testy dodane, ale Gradle/JUnit/Robolectric NOT RUN.
- Granica gwarancji: równoczesna awaria obu trwałych zapisów markerów i usunięcia aliasu Keystore uniemożliwia zagwarantowanie blokady po restarcie.
- Następny krok: Android/Robolectric fault injection na rzeczywistym SharedPreferences i Keystore, potem testy równoległych mutacji store. Nie aktywować transportu.


## 0.6.81-SOURCE — współbieżne publikowanie trwałego markera Companion

- Baza: 0.6.80-SOURCE.
- Plik tymczasowy blokady ma unikalną nazwę (`Files.createTempFile`), co usuwa kolizję stałej nazwy przy równoległych writerach z różnych instancji store. Docelowy marker nadal jest publikowany atomowym rename.
- Cleanup zachowuje pierwotny błąd i dopina ewentualny błąd sprzątania jako `suppressed`.
- Test Robolectric dla dwóch instancji i 32 równoległych zapisów dodany, ale nieuruchomiony. Gradle/JUnit/Robolectric/Keystore/APK/device pozostają NOT RUN.
- Następny audyt: fault injection rzeczywistego `SharedPreferences.commit`, awarii `Files.move`, restartu procesu oraz potwierdzenie, że recovery nie może odblokować trust przy niepełnym usunięciu któregokolwiek markera. Transport nadal BLOCKED.


## Aktualizacja 0.6.82-SOURCE — trwałość markerów
- 0.6.81 usunęło kolizję nazw plików tymczasowych markera. 0.6.82 dodaje fsync katalogu po atomowym rename i po usunięciu pliku blokady, aby utrwalić zmiany wpisów katalogowych przed raportowaniem publikacji/odblokowania.
- Host JDK 21/Linux NIO: 100/100 cykli PASS; test Robolectric dodany, NOT RUN. Zachowanie na Androidzie i OEM filesystemie wymaga walidacji w Android Studio/urządzeniu.
- Następny etap: fault injection dla commit/move/fsync/Keystore i crash matrix dla wipe/recovery; transport pozostaje zablokowany do zamknięcia pkt 5.


## Aktualizacja 0.6.83-SOURCE — kontrolowane granice awarii
- Dodano wewnętrzne punkty fault injection do rzeczywistego `CompanionProvisioningStore`: preferences, atomic move, directory fsync, marker unlink, Keystore alias i wipe.
- Dodano dwa testy Robolectric dla czterech granic publikacji i czterech granic usuwania markera; ADDED / NOT RUN.
- Niezależny hostowy harness NIO: 200/200 wstrzykniętych przypadków PASS; nie jest to test Android store ani restartu urządzenia.
- Otwarte: adapter pozwalający wymusić `commit() == false` i błąd `KeyStore.deleteEntry()`, pełna macierz restartu, serializacja arbitralnych instancji store. `synchronized(this)` jest per instancja; aplikacja używa singletonu application-scoped w jednym procesie, ale multi-process nie jest wspierany ani deklarowany.
- Transport Companion pozostaje BLOCKED.


## Aktualizacja 0.6.84-SOURCE — adaptery I/O i wspólny monitor procesu
- Wprowadzono adapter I/O dla rzeczywistych commitów preferences, operacji na markerze, fsync katalogu i usuwania aliasu Keystore; testy mogą wymusić konkretne wyniki operacji.
- Monitor i fail-closed latch są współdzielone przez instancje store w obrębie procesu aplikacji w obrębie jednego procesu. To zamyka znany wyścig wynikający z `synchronized(this)` per instancja, ale nie zapewnia synchronizacji międzyprocesowej.
- Dodano testy Robolectric dla `commit() == false`, błędu alias deletion i serializacji dwóch instancji. Źródło produkcyjne i testowe przeszło `kotlinc -Werror` z atrapami API; osobny hostowy harness rzeczywistego store z atrapami Context/SharedPreferences: 3/3 PASS. Testy Robolectric nieuruchomione, urządzenie/OEM filesystem nadal wymagane.
- Transport Companion nadal nieaktywny.


## Aktualizacja 0.6.85-SOURCE — linearizacja mutacji i wspólny publisher revocation
- Baza: 0.6.84-SOURCE; app nadal `versionName=0.6.54`, `versionCode=80`.
- Audyt potwierdził wspólny monitor procesu dla `promotePeerRotation()`, `revokePeer()` i `wipeAllTrust()`, z fence pod monitorem i cleanup socketów poza monitorem.
- Wykryto i naprawiono rozjazd zakresu: publisher był per-instancja, mimo że monitor/latch były process-wide. Publisher jest teraz elementem `ProcessTrustState`, więc mutacja z dowolnej instancji store w procesie uruchamia fence runtime zbindowanego do innej instancji.
- Host JVM race tests registry: 3/3 × 5 = 15/15 PASS; `kotlinc -Werror` PASS. Test integracyjny dwóch instancji store + runtime: Robolectric ADDED / NOT RUN.
- Observer callbacks pozostają poza monitorem i ich kolejność między konkurencyjnymi operacjami nie jest gwarantowana; obecnie brak produkcyjnych rejestracji zwykłych observerów. Fence sesji jest synchroniczny i odbywa się pod monitorem.
- Gradle/JUnit/Robolectric/Android Keystore/APK/device NOT RUN. Transport Companion nadal BLOCKED.
- Następny krok: uruchomić testy Robolectric/Gradle i zweryfikować integrację na urządzeniu; dopiero potem rozważyć zamknięcie bramki pkt 5.


## Aktualizacja 0.6.86-SOURCE — przeniesienie metod pracy do MASTER-a
- Baza: 0.6.85-SOURCE. W `AgentPrompts` usunięto bezwarunkową, fałszywą deklarację pełnego dostępu do repozytorium. Agent ma opierać się tylko na rzeczywiście udostępnionych danych i zgłaszać braki.
- Role techniczne otrzymują protokół: cel/kryteria, fakty vs hipotezy, minimalny patch, testy awarii, alternatywne metody bez udawania wyników, rozróżnienie poziomów testów, regresje i rzetelny raport. MASTER dostaje dodatkowo reguły delegowania, rozstrzygania konfliktów, pamięci z pochodzeniem i self-checku.
- `AI_MASTER_ENGINEERING_PLAYBOOK_PL.md` zapisuje docelowe niezmienniki i pętlę rozwiązywania problemu. To instrukcja, nie granica bezpieczeństwa; kolejnym krokiem jest typowany model dowodów/checkpointów w runtime.
- `AgentPromptsTest`: 4/4 PASS, `kotlinc -Werror` z lokalnym shimem JUnit. Gradle/JUnit/Robolectric/APK/Android NOT RUN.
- Companion pozostaje nieaktywny. Następny etap techniczny: typowane dowody w runtime MASTER-a; osobno nadal wymagane Gradle/Robolectric i Android Keystore dla zamknięcia Companion pkt 5.


## Aktualizacja 0.6.87-SOURCE — typowane dowody i ocena kompletności
- Baza: 0.6.86-SOURCE. Dodano `VerificationEvidence` z rozdzieleniem źródła i statusu; deklaracja agenta ma wyłącznie `CLAIM_ONLY`, wynik narzędzia wymaga `executionId`, a `NOT_RUN` nie może być promowany do PASS.
- `VerificationAssessment` sprawdza komplet wymaganych rodzajów dowodów, nadaje FAIL pierwszeństwo, oznacza braki/claim/NOT_RUN jako INCOMPLETE i odrzuca łączenie dowodów z różnych zadań.
- `ProjectTestRunner.Result` zwraca dowody dla kontroli integralności, snapshotu, zakresu patcha i PROJECT_TESTS. Ostatni pozostaje NOT_RUN; integralność nie oznacza kompilacji ani testów.
- Walidacja lokalna: `kotlinc -Werror`, VerificationEvidence 6/6 i ProjectTestRunner 3/3 = 9/9 PASS. Gradle/JUnit/Robolectric/APK/device NOT RUN.
- Otwarte: trwały zapis dowodów w `TaskCheckpointStore`, zasilenie ledgeru z rzeczywistych wywołań narzędzi i przekazanie do MASTER synthesis. Nie wolno traktować tekstu modelu jako źródła `TOOL_EXECUTION`.


## Aktualizacja 0.6.88-SOURCE — trwałe dowody weryfikacji
- Baza: 0.6.87-SOURCE. Dodano wersjonowany `VerificationEvidenceStore` z limitem 800 rekordów, 1 MB ledgeru, idempotencją ID i fail-closed zachowaniem dla uszkodzonego formatu.
- Android adapter używa `SharedPreferences.commit()` i zwraca wynik zapisu. Przepływ weryfikacji patcha zapisuje typowane dowody oraz pokazuje błąd zapisu zamiast deklarować sukces.
- Wspólny monitor serializuje operacje między instancjami w jednym procesie; multi-process nie jest wspierany.
- Testy lokalne: prompt 4/4, evidence model 6/6, ProjectTestRunner 3/3, store 11/11 = 21/21 PASS. Android adapter stub compile PASS. Gradle/Android build NOT RUN.
- Następny krok: odczyt ledgeru per task i ograniczone przekazanie dowodów do MASTER self-check/syntezy; źródło TOOL_EXECUTION musi zostać podłączone do kontrolowanego hostowego rejestru narzędzi.


## Aktualizacja 0.6.89-SOURCE — MASTER self-check oparty na dowodach
- MASTER dostaje regułę, że COMPLETED podagenta oznacza raport, nie potwierdzony fakt. Brak ledgeru oznacza brak potwierdzenia.
- `MasterResponseCheck` rozdziela dowody testów (UNIT/INTEGRATION/ROBOLECTRIC), kompilacji (COMPILATION) i Android build (ANDROID_BUILD). Inna kategoria PASS nie może autoryzować twierdzenia.
- Instrukcja syntezy MainActivity nie żąda bezwarunkowo „zweryfikowanej” odpowiedzi.
- Testy lokalne: prompt 4/4, response self-check 11/11, evidence model 6/6, ProjectTestRunner 3/3, ledger 11/11 = 29/29 PASS. Gradle/Android build NOT RUN.
- Następny krok: powiązać chat/task/snapshot IDs i przekazywać MASTER-owi tylko dowody odpowiadające bieżącemu zadaniu.


## 0.6.90-SOURCE — wiązanie zakresu dowodów MASTER-a

- `VerificationEvidence` rozróżnia taskId, chatId i snapshotId; assessment odrzuca mieszanie zakresów.
- `VerificationEvidenceStore.forScope()` wykonuje dokładny odczyt po trzech identyfikatorach.
- Ledger V2 zachowuje kompatybilność odczytu V1; rekordy historyczne pozostają jawnie unscoped.
- ChangeSet przechowuje chatId; ProjectTestRunner przypisuje dowody do prawidłowego task/chat/snapshot.
- Testy lokalne shim: 20/20 PASS. Pełny Gradle/Android build NOT RUN.
- Następny krok: host-owned ToolExecutionRegistry z executionId generowanym przez aplikację, potem exact-scope evidence resolver do syntezy MASTER-a.


## 0.6.91-SOURCE — host-owned rejestr wywołań narzędzi

- `ToolExecutionRegistry` generuje executionId po stronie hosta i wydaje uchwyt związany z instancją registry.
- `begin()` zapisuje NOT_RUN przed startem adaptera; `complete()` zapisuje PASS/FAIL tylko po zużyciu właściwego uchwytu; `abandon()` zapisuje NOT_RUN.
- Awaria zapisu początku blokuje start; awaria zapisu zakończenia pozostawia NOT_RUN.
- Testy: Evidence/Store/Registry 25/25 PASS; ProjectTestRunner 3/3 PASS; łącznie 28/28 lokalnych shim PASS.
- Adaptery narzędzi nie są jeszcze podłączone; nie deklarować, że runtime rejestruje już wszystkie operacje.
- Następny krok: podłączyć rzeczywiste host adapters, potem exact-scope resolver i assessment w MASTER synthesis.

## Aktualizacja 0.6.92-SOURCE — integracja hostowego adaptera
- Pierwszy rzeczywisty call site `ToolExecutionRegistry` to ręczna weryfikacja patcha w `MainActivity`.
- `EvidenceTrackedToolAdapter` zapisuje NOT_RUN przed operacją; zwykły powrót adaptera nie oznacza PASS testów, a jedynie PASS wykonania hostowego adaptera. Wyjątki i anulowania mają osobne ścieżki.
- Dowody domenowe `ProjectTestRunner` pozostają odrębne od dowodu uruchomienia narzędzia. PROJECT_TESTS nadal NOT_RUN bez sandboxa.
- Starsze wpisy bez chatId są jawnie nieśledzone, bez sztucznego przypinania do rozmowy.
- Walidacja lokalna 38/38 shim-JUnit PASS; Android/Gradle NOT RUN.
- Następny etap: exact-scope resolver task/chat/snapshot, a następnie przekazanie tylko zweryfikowanego, ograniczonego zestawu dowodów do MASTER synthesis.


## Aktualizacja 0.6.93-SOURCE — exact-scope resolver dowodów
- Dodano `VerificationEvidenceResolver`: wymaga taskId/chatId/snapshotId oraz listy wymaganych rodzajów dowodów; pobiera wyłącznie exact scope.
- Claimy agentów są zachowane jako oddzielny kontekst, ale nie mogą spełnić żadnej wymaganej kontroli. Wynik TOOL_EXECUTION wymaga pasującego hostowego NOT_RUN start marker z tym samym executionId i kind oraz późniejszego timestampu. Orphan, niezgodny rodzaj i wynik przed startem są odrzucane.
- NOT_RUN nowego uruchomienia blokuje starszy PASS; FAIL pozostaje nadrzędny do czasu późniejszego, rzeczywistego PASS. Uszkodzony ledger i niekompletny scope działają fail-closed.
- Lokalna walidacja: `kotlinc -Werror` PASS; 48/48 shim-JUnit PASS. Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN.
- Następny krok: podłączyć resolver do MASTER synthesis z aktywnym scope pochodzącym z hosta/orchestratora, a nie z tekstu modelu; assessment przekazać do `MasterResponseCheck`. Brak scope nie może uruchamiać szerokiego fallbacku.


## Aktualizacja 0.6.94-SOURCE — integracja dowodów z MASTER synthesis
- `MasterEvidenceContextBuilder` wybiera pojedynczy ChangeSet wyłącznie po jawnej referencji do changeSetId/taskId/snapshotId w prompt użytkownika i tylko w aktywnym czacie. Brak referencji lub wiele dopasowań nie uruchamia fallbacku.
- Ograniczony kontekst exact-scope trafia do MASTER_ONLY i końcowej syntezy zespołu; assessment trafia do `MasterResponseCheck`.
- 53/53 lokalne shim-JUnit PASS; `kotlinc -Werror` PASS. Gradle/Robolectric/Android build/APK NOT RUN.
- Ograniczenie: identyfikator musi być jawnie wskazany. Następny krok: hostowy aktywny scope w orkiestratorze, nie heurystyka „najnowszego” ChangeSet.


## Aktualizacja 0.6.95-SOURCE — host-owned active evidence scope
- Zamiast parsować ID z promptu, host wybiera dokładnie jeden zastosowany ChangeSet z aktywnego czatu (APPLIED_UNVERIFIED/APPLIED, snapshotId wymagany).
- Zero/multiple/malformed scope daje brak dowodów; nigdy nie wybieramy „najnowszego”.
- Obie ścieżki MASTER korzystają z `buildForHostScope()`. 59/59 lokalnych shim-JUnit PASS; `kotlinc -Werror` PASS.
- Następny krok: jawny `activeChangeSetId` w host state/orkiestratorze, z lifecycle/resetem przy zmianie czatu/workspace, rollback i wielu ChangeSet. Dopiero wtedy rozwiążemy bezpiecznie przypadek wielu zmian bez arbitralnego wyboru.


## Aktualizacja 0.6.96-SOURCE — jawny aktywny ChangeSet hosta
- `HostActiveChangeSetStore` przechowuje jeden trwały aktywny zakres dowodów; tuple wiąże czat, dokładną rewizję workspace (SHA-256 `current.manifest`), ChangeSet, task i snapshot.
- MASTER nie wybiera już z listy zastosowanych zmian. Host wskazuje dokładny ID; resolver sprawdza jednoznaczność i zgodność statusu/identyfikatorów.
- Aktywacja następuje dopiero po apply + snapshot + `APPLIED_UNVERIFIED`; rollback/odrzucenie unieważnia wskaźnik. Zmiana czatu lub manifestu nie odziedziczy starego scope.
- `ChangeSetStore` używa `commit()` i jawnie zgłasza błąd zapisu. Usunięto potencjalny lock inversion między store'ami.
- Walidacja lokalna 65/65 PASS. Dodano testy Robolectric store i identity manifestu, ale nie uruchomiono ich. Gradle/Robolectric/Android build/APK/device NOT RUN.
- Następny krok: jawny wynik odczytu `ChangeSetStore` (VALID/CORRUPT zamiast pustej listy przy uszkodzeniu), globalna serializacja operacji import/apply między instancjami `ProjectWorkspaceStore`, audyt usuwania workspace/czatu, następnie uruchomienie pełnych testów Gradle.


## Aktualizacja 0.6.97-SOURCE — fail-closed ChangeSet ledger
- `ChangeSetStore.readResult()` zwraca VALID/CORRUPT; pusty rejestr jest poprawnym VALID-empty, nieparsowalny JSON, wadliwy rekord, duplikat ID, nieznane statusy i błędne typy danych są CORRUPT.
- Mutacje nie zapisują, jeśli ledger jest uszkodzony. Nie ma już ścieżki, która po błędzie odczytu zastępuje dane pustą listą.
- `HostActiveChangeSetStore` odrzuca aktywny zakres przy corrupt ledger. UI Command Center/pamięć/weryfikacja patcha pokazują korupcję i blokadę.
- Harness resolvera 65/65 PASS; nowe testy Robolectric ADDED/NOT RUN; Gradle/Android build/APK/device NOT RUN.
- Następny krok: wspólny process lock dla import/apply/rollback/odczytów workspace, ponieważ instancje `ProjectWorkspaceStore` mają osobne monitory; następnie pełny Gradle/Robolectric.


## Aktualizacja 0.6.98-SOURCE — process-wide workspace lock
- Dodano fair/reentrant `ProjectWorkspaceOperationLock`; import, apply, preview, analiza, runner, rollback i odczyty hostowe korzystają ze wspólnego gate zamiast monitorów per instancja.
- Aktywacja active ChangeSet porównuje SHA-256 manifestu i zapisuje wskaźnik pod wspólną blokadą.
- UI używa nieblokującego `tryWithLock()` i nie czeka na operację workspace w tle.
- Testy locka: mutual exclusion, reentrancy i non-blocking probe; harness 68/68 PASS; ChangeSet compile shim `kotlinc -Werror` PASS. Gradle/Robolectric/Android build/APK/device NOT RUN.
- Następny krok: integracyjne wyścigi import-vs-apply/rollback/preview oraz active scope-vs-chat switch na dwóch niezależnych instancjach `ProjectWorkspaceStore`.

## Aktualizacja 0.6.99-SOURCE — rollback chroniony tożsamością workspace
- Audyt wykrył okno między końcem weryfikacji a automatycznym rollbackiem: świeży import mógł zostać nadpisany snapshotem poprzedniego projektu. Naprawa: identity + runner pod jednym lockiem, następnie rollback tylko po dokładnym porównaniu manifest identity.
- Import i unieważnienie active evidence pointer są wykonywane w jednej sekcji krytycznej, aby nie wyczyścić wskaźnika nowego patcha przez opóźniony callback importu.
- Błędy invalidation nie fałszują informacji o powodzeniu importu/restore; są raportowane osobno, a kontrola identity pozostaje fail-closed.
- `ProjectWorkspaceIdentityGuard` testuje zgodną, zmienioną i brakującą identity. Dodatkowo testy lock release i kolejności import-invalidation kontra nowa aktywacja.
- Walidacja: 73/73 lokalne shim-JVM PASS (`kotlinc -Werror`); ChangeSet/HostActive compile shim PASS. Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN.
- Następny krok: audyt transakcyjności ChangeSet ledger ↔ workspace (awarie między apply, recordSnapshot, status transition i active pointer), a potem przypadki usuwania/odtwarzania workspace i rotacji czatu.

## Aktualizacja 0.6.100-SOURCE — transakcyjność patch preview/apply
- Preview przechowuje SHA-256 manifestu i chatId z chwili przygotowania; po peer review i finalnej zgodzie apply odrzuca nieaktualny workspace lub czat. Kontrola identity + integralności + apply działa w jednej sekcji lock.
- Naprawiono częściowe niepowodzenia między apply, recordSnapshot, zmianą statusu i aktywacją scope. Przed trwałym `APPLIED_UNVERIFIED` aplikacja próbuje bezpiecznie przywrócić snapshot i uzgodnić ledger. Po trwałym statusie brak aktywnego pointera jest raportowany bez nieuzasadnionego rollbacku.
- `ProjectWorkspaceStore.applyPreview()` przy błędzie po podmianie wykonuje wewnętrzny recovery do zweryfikowanego snapshotu; zachowuje ten snapshot do zakończenia kontroli identity.
- Walidacja: 73/73 lokalny resolver/lock harness; ChangeSet/HostActive compile shim PASS; workspace core `kotlinc -Werror` PASS; JVM smoke import-preview-apply-restore-stale identity PASS. Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN.
- Następny krok: zbudować fault-injection harness dla punktów zapisu ChangeSet/SharedPreferences i filesystem, zweryfikować wszystkie ścieżki kompensacji, a potem dodać idempotentną rekoncyliację po restarcie procesu.

## 0.6.101 — trwały dziennik swapu workspace
- Wprowadzono fsync + atomic move dziennika przed pierwszym rename; commit wymaga poprawnego manifestu, oczekiwanego SHA-256 i pełnej integralności drzewa.
- Recovery rozróżnia commit, rollback do poprzedniego workspace i niedokończony pierwszy import; uszkodzony dziennik blokuje dostęp fail-closed.
- Testy JVM fault-state: 4/4 PASS; workspace core `kotlinc -Werror` PASS. Gradle/Robolectric/Android build/APK/device NOT RUN.
- Następny krok: durable/idempotent recovery koordynujący filesystem z ChangeSetStore i HostActiveChangeSetStore po restarcie procesu.

## 0.6.102 — patch receipt i recovery ledgeru po restarcie
- Journal v2 niesie ChangeSet/chat/snapshot/base identity; reader zachowuje zgodność z v1.
- Trwały receipt rozróżnia commit i rollback. Startup recovery odtwarza ledger i active pointer tylko po ścisłej walidacji snapshotu, chatId i workspace identity.
- Nierozliczony journal/receipt blokuje kolejny patch; błędy odczytu są fail-closed.
- Testy JVM recovery 12/12 PASS; workspace core `kotlinc -Werror` PASS; resolver 73/73 PASS; ChangeSet compile shim PASS. Android/Gradle/Robolectric/APK/device NOT RUN.
- Następny krok: Robolectric/Android integration test rzeczywistego procesu startup, SharedPreferences commit fail, powtórnego recovery, import/chat switch kontra receipt oraz uszkodzonego ledgeru.


## 0.6.103 — recovery policy testability
- Decyzje startup reconciliation wydzielone do czystej polityki Kotlin i pokryte JVM harness 11/11.
- Integracja MainActivity sprawdzona statycznie 8/8; nadal wymaga pełnego Android compile/Robolectric.
- Następny krok: testowalny storage seam dla ChangeSet/HostActive SharedPreferences, awarie commit/read/corrupt JSON oraz rzeczywiste testy startup recovery.


## 0.6.104 — trwały storage i ścieżki awarii SharedPreferences
- `StringPreferenceStore` oddziela ChangeSet/HostActive od bezpośredniego API SharedPreferences i pozwala wstrzykiwać kontrolowany backend.
- `ChangeSetStore` zachowuje fail-closed przy wyjątku odczytu/uszkodzonym JSON; zapis z `commit()==false` nie zwraca sukcesu i nie nadpisuje istniejącego uszkodzonego ledgeru.
- `HostActiveChangeSetStore` jawnie raportuje niepowodzenie zapisu/unieważnienia oraz zwraca INVALID przy wyjątku odczytu.
- Dodano 8 testów Robolectric dla read exception, corrupt JSON, commit false/exception i remove failure. Testy dodane, ale nieuruchomione bez Gradle/Robolectric.
- Lokalny compile shim `kotlinc -Werror` PASS; wcześniejsze harnessy recovery 12/12, policy 11/11, resolver 73/73 PASS.
- Następny krok: rzeczywiste Robolectric testy, a następnie startup recovery + crash injection i współbieżność import/chat switch.


## 0.6.105 — spójny wstrzykiwany ledger aktywnego zakresu
- `HostActiveChangeSetStore.setActive/readActive` korzystają z jawnego `ledgerStorage`; nie omijają już wstrzykniętego backendu podczas autoryzacji active pointera. Domyślna konfiguracja produkcyjna zachowuje dotychczasowe SharedPreferences.
- Dwa dodatkowe testy Robolectric sprawdzają corrupt ledger i read exception na źródle ledgeru; testy dodane, ale NOT RUN.
- Compile shim PASS; workspace 12/12, recovery policy 11/11, resolver 73/73 PASS.
- Następny krok: testy storage fault podczas startup recovery, powtarzane recovery i wyścigi import/chat switch. Companion transport nadal BLOCKED.

## 0.6.106 — współbieżność process lock
- Dodano odrębny host JVM harness produkcyjnej blokady workspace: 5/5 PASS, 1200 prób z 8 wątków, reentrancy, try-lock i wyjątki.
- Regression: recovery policy 11/11, workspace transaction recovery 12/12, resolver/lock 73/73, ChangeSet/HostActive compile shim PASS.
- Nadal OPEN: rzeczywisty Robolectric/Android startup recovery, SharedPreferences failure w trakcie recovery oraz przeplot z importem i zmianą czatu.
- Companion transport pozostaje BLOCKED.


## Kontynuacja po 0.6.107-SOURCE
- 0.6.107 dodaje hostowy fault model protokołu startup receipt recovery: crash/retry na czterech granicach zapisu, rollback, chat switch, późniejsza zmiana workspace identity i idempotent ack. Testy 8/8 PASS; korzystają z produkcyjnej polityki recovery, ale nie wykonują MainActivity ani Android storage.
- Następny krok: wydzielić rzeczywisty koordynator recovery z `MainActivity` z portami dla workspace/ledger/active scope. Produkcyjny kod i testy muszą używać tego samego koordynatora; testy mają wstrzykiwać awarię po każdym zapisie. Zachować lock order i warunek exact chatId + workspace identity.
- Dopiero potem Robolectric startup/process recreation i pełny Gradle, gdy dostępne. Nie uznawać modelu JVM za dowód Android runtime. Companion transport nadal BLOCKED; aplikacja nadal 0.6.54/code 80.


## 0.6.108 — produkcyjny koordynator patch receipt recovery
- Wydzielono `PatchReceiptRecoveryCoordinator`; MainActivity i JVM harness używają tego samego algorytmu kolejności recovery.
- Backend port izoluje platform I/O, ale nie zastępuje jeszcze testu rzeczywistego Android storage/process recreation.
- Koordynator harness 8/8 PASS; policy 11/11; protocol 8/8; workspace transaction 12/12; lock 5/5; resolver 73/73; ChangeSet compile shim PASS.
- Następnie: integracyjne testy z prawdziwym workspace filesystem, awarie ledgeru, powtórne recovery, import/chat switch i Robolectric. Companion transport nadal BLOCKED.


## 0.6.109 — rzeczywisty workspace filesystem w recovery harness
- Połączono produkcyjny `PatchReceiptRecoveryCoordinator` z produkcyjnym `ProjectWorkspaceStore` i `ProjectPatchEngine` na katalogach tymczasowych.
- 5/5 scenariuszy: idempotentne reconcile + exact ack, retry po błędzie statusu, odmowa cross-chat, rzeczywisty rollback do bazowej workspace identity + invalidation; import vs recovery serializowany produkcyjną blokadą bez deadlocku.
- Regresje: coordinator 9/9, protocol 8/8, policy 11/11, workspace transaction 12/12, lock 5/5, resolver 73/73.
- Ledger backend w harnessie jest adapterem JVM; SharedPreferences/Robolectric/Android process death nadal NOT RUN.
- Następny krok: test współbieżności import/recovery z realnym store pod `ProjectWorkspaceOperationLock`, audyt lock order i aktywacji po zmianie czatu/workspace. Companion transport BLOCKED.


## 0.6.110 — recovery lock-order hardening i concurrency races
- Usunięto monitor acquisition z `isCurrentChat()`; `currentChatId` jest volatile. To zamyka wykrytą ścieżkę odwrotnej kolejności: aktywacja (activity monitor → workspace lock) kontra apply (workspace lock → check czatu).
- Nowy concurrency harness: 3/3 PASS, w tym deterministyczny test bez deadlocku, chat switch po reconcile i równoległe recovery callers.
- Rzeczywisty filesystem integration harness rozszerzony do 7/7: równoległe recovery uzgadnia jeden receipt raz; import po prepare zmienia identity i blokuje aktywację starego scope.
- Regresje w tej iteracji: coordinator 9/9, protocol 8/8, policy 11/11, workspace lock 5/5, resolver 73/73, ChangeSet compile shim PASS. Łącznie 116 jawnych przypadków PASS.
- `workspace_transaction_recovery_harness` rerun zakończył się timeoutem podczas kotlinc; ostatni potwierdzony wynik 12/12 pochodzi z 0.6.109 i nie jest liczony do bieżącej iteracji. Gradle/Robolectric/Android/APK/device NOT RUN.
- Następny krok: audyt wszystkich miejsc, w których workspace lock może być trzymany podczas wejścia do metod synchronizowanych; test seam dla przeplotu reconcile/chat switch/import/activate/ack. Companion transport nadal BLOCKED.

## 0.6.111 — atomowość activate → acknowledge w startup recovery
- Dodano produkcyjny `PatchReceiptRecoveryCommitGate`. Końcowa kontrola czatu/workspace, zapis aktywnego zakresu, ponowna kontrola czatu, invalidation przy chat switch i acknowledge receipt są wykonywane pod jedną process-wide blokadą workspace.
- To usuwa okno, w którym import mógł wejść pomiędzy aktywację pointera a acknowledge receipt. Aktywacja, która zawiedzie przy niezmienionym scope, zachowuje receipt do retry.
- Testy tej iteracji: commit gate 5/5, concurrency 3/3, coordinator 9/9, protocol 8/8, policy 11/11, workspace lock 5/5; razem 41 PASS + MainActivity static guard.
- Filesystem integration i verification resolver rerun zakończyły się timeoutem kompilacji; nie przenoszę poprzednich wyników jako bieżących. Gradle/Robolectric/Android/APK/device NOT RUN.
- Następny punkt: uruchomić testy fault injection dla SharedPreferences/HostActiveChangeSetStore na rzeczywistym Robolectric/Gradle, a w środowisku hostowym rozszerzyć commit-gate o awarie zapisu active pointer i ack oraz powtórne uruchomienie po częściowym sukcesie. Companion transport nadal BLOCKED; app 0.6.54/code 80.

## 0.6.112 — fault matrix aktywnego pointera i acknowledge
- `PatchReceiptRecoveryCommitGate` rozróżnia `INVALIDATION_FAILED`; po nieudanym trwałym usunięciu aktywnego pointera nie potwierdza receipt. `MainActivity` rozdziela diagnostycznie activation/invalidation/ack failure.
- Host fault harness 7/7 PASS; concurrency 3/3; coordinator 9/9; protocol 8/8; policy 11/11; razem 38 PASS + static guard.
- Dodano trzy testy Robolectric w `ChangeSetStoreTest` dla commit failure active pointer + retry, ack failure po zapisanym pointerze + retry oraz invalidation failure po chat switch. Testy ADDED / NOT RUN.
- Filesystem integration, workspace lock i verification resolver rerun TIMEOUT; nie przenoszę wcześniejszych wyników jako bieżących. Gradle/Robolectric/Android/APK/device NOT RUN.
- Następny punkt: uruchomić pełny `ChangeSetStoreTest` i rzeczywisty startup recovery w Robolectric/Gradle; jeśli środowisko nadal blokuje Gradle, rozszerzyć hostową integrację backendu ledgeru o fault injection dla commit/read/remove i retry całego coordinatora. Companion transport BLOCKED; app 0.6.54/code 80.


## Aktualizacja 0.6.113 — recovery retry fault matrix
- Hostowy harness `PatchReceiptRecoveryCoordinator` rozszerzony do 13/13 PASS: read exception, corrupt ledger, retry po częściowym zapisie snapshotu/statusu i retry rollbacku.
- Commit gate 7/7, policy 11/11, protocol 8/8; łącznie 39 hostowych przypadków PASS. Statyczne guardy 7/7 PASS.
- Concurrency harness timeout w bieżącej iteracji; pozostałe cięższe harnessy oraz Robolectric/Gradle/Android build NOT RUN.
- Następny priorytet: testy rzeczywistych adapterów Android `ChangeSetStore`/`HostActiveChangeSetStore` z wstrzykiwanymi błędami `StringPreferenceStore`; potem startup retry/process death w Robolectric. Nie aktywować Companion transportu.


## 0.6.114 — host fault matrix rzeczywistych magazynów ChangeSet
- Dodano harness, który uruchamia produkcyjne `ChangeSetStore`, `HostActiveChangeSetStore` i `StringPreferenceStore` z kontrolowanym fake storage oraz shimami Android Context/JSON. 10/10 PASS przy `kotlinc -Werror`.
- Pokryto odczyt exception/corrupt JSON, zapis false/exception, active pointer write failure + retry, invalidation remove false/exception + retry i odmowę aktywacji na corrupt ledger.
- Regresje tej iteracji: coordinator 13/13, commit gate 7/7, policy 11/11, protocol 8/8; razem 49 hostowych przypadków PASS.
- Ważne: testy wykonują produkcyjne klasy magazynów, ale backend storage i Android/JSON API są shimowane. To nie jest test SharedPreferences/Robolectric ani process-death. Gradle/Android/APK/device NOT RUN.
- Następny krok: uruchomić prawdziwy adapter Android SharedPreferences w Robolectric, pełny startup recovery po rekreacji Activity/procesu, awarie commit/remove i retry. Companion transport pozostaje BLOCKED; app 0.6.54/code 80.


## 0.6.116 — walidacja wejściowego receipt przed startup recovery
- Koordynator odrzuca niekompletną tożsamość receipt i sprzeczne flagi rollback, zanim wywoła backend.
- Nowe testy negatywne: brak backend I/O dla niekompletnego i sprzecznego receipt.
- Host regressions: 61/61 PASS; compile shim PASS. Filesystem integration i resolver TIMEOUT.
- Android/Robolectric nadal NOT RUN. Preflight wskazuje brak wrapper JAR, Gradle, Android SDK oraz DNS do `services.gradle.org`.
- Następny etap: odtworzyć pełne środowisko Android build poza hostem; uruchomić Robolectric startup recovery i SharedPreferences. Companion transport BLOCKED.


## 0.6.117 — workspace identity fence dla startup recovery
- Koordynator sprawdza bieżącą workspace identity przed odczytem ledgeru; akceptuje wyłącznie before/target identity z receipt. Obcy workspace zatrzymuje recovery bez ledger I/O i bez mutacji.
- Coordinator 18/18, policy 11/11, protocol 8/8, concurrency 3/3 + lock-order guard, workspace lock 5/5, ChangeSet fault 10/10 = 55 hostowych przypadków PASS.
- Commit gate harness TIMEOUT; filesystem/resolver NOT RUN; Gradle/Robolectric/Android/APK/device NOT RUN.
- Następny krok: odtworzenie pełnego środowiska Gradle 8.13 + Android SDK 36 i prawdziwe testy SharedPreferences/startup/process recreation. Companion transport BLOCKED; app 0.6.54/code 80.


## 0.6.118 — workspace identity pre-fence przed rollbackiem
- Wykryto lukę kolejności: `recoverRequestedRollback()` było wywoływane przed workspace identity fence. Przeniesiono kontrolę przed backend rollback oraz pozostawiono ponowną kontrolę po rollbacku (bo legalnie zmienia identity target→before).
- Nowy test dowodzi, że obcy workspace blokuje rollback już na wejściu; istniejący test potwierdza brak snapshot i ledger I/O.
- Regresje: coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, workspace lock 5/5, ChangeSet fault 10/10, commit gate 7/7, filesystem integration 7/7, resolver 73/73 = 136 host PASS; lock-order guard PASS.
- Gradle/Robolectric/Android SharedPreferences/process recreation/Android/APK/device NOT RUN. App 0.6.54/code 80; Companion transport BLOCKED.
- Następny krok: dalsze testy przeplotu import/restore/recovery oraz rzeczywiste Robolectric po udostępnieniu kompletnego środowiska Android.

## Aktualizacja 0.6.119 — restore/recovery race hardening
- Zabezpieczono commit gate: stale chat/workspace scope powoduje invalidację aktywnego zakresu przed ACK; nieudana invalidacja pozostawia receipt do retry.
- Dodano hostowe testy rzeczywistego filesystemu dla odtworzenia store po przerwanym rollbacku oraz restore konkurującego z recovery.
- Wynik: 147 hostowych przypadków PASS + MainActivity lock-order static guard PASS.
- Android SharedPreferences/Robolectric/process-death/Gradle/Android build/APK/device nadal NOT RUN; Companion Transport BLOCKED.
- Następny kamień milowy: rzeczywiste testy Android startup recovery po udostępnieniu kompletnego środowiska Gradle + Android SDK; nie zastępować ich shimami.

## 0.6.120-SOURCE — active pointer / ledger divergence

- `HostActiveChangeSetStore.readActive()` odróżnia chwilowy błąd odczytu ledgeru od definitywnej sprzeczności z poprawnym ledgerem.
- Gdy VALID ledger nie zawiera dokładnie jednego wskazanego ChangeSet albo nie zgadza się chatId/taskId/snapshotId/status, active pointer jest trwale unieważniany.
- Gdy ledger jest CORRUPT lub odczyt rzuca wyjątek, wskaźnik pozostaje zachowany, ale odczyt jest INVALID; nie utożsamiamy braku danych z dowodem nieaktualności.
- Błąd usunięcia wskaźnika pozostaje jawnie INVALID i wymaga retry.
- Host fault matrix 13/13 PASS; pełna regresja hostowa 162 PASS. Trzy testy Robolectric dodane, NOT RUN.
- Następna bramka: zweryfikować semantykę `SharedPreferences.commit() == false` (w tym rozbieżność pamięć/dysk), a następnie uruchomić rzeczywisty Robolectric/startup recovery. Do tego czasu nie deklarować Android build.


## Aktualizacja 0.6.121 — ambiguous SharedPreferences commit
- Adapter `AndroidStringPreferenceStore` próbuje odtworzyć poprzednią wartość po false/exception z commit; operacja nadal zwraca failure.
- Fake SharedPreferences symuluje commit, który aktualizuje mapę procesu, ale zwraca false; testy potwierdzają kompensację write/remove.
- To nie jest gwarancja trwałości po awarii dysku: gdy kompensacyjny commit również zawiedzie, potrzebny jest jawny stan niejednoznaczny i reconciliation/journal.
- Kolejny etap: zaprojektować obsługę double-commit failure i odtworzenie po restarcie; potem realne Robolectric/SharedPreferences. Companion transport nadal BLOCKED.


## Aktualizacja 0.6.122 — ambiguous active-pointer quarantine + startup evidence gate
- Po niepotwierdzonym `setActive()` system quarantines zarówno próbny rekord, jak i rekord aktualnie widoczny w adapterze. To chroni także przypadek, gdy commit i kompensacja commit zwracają false, a pamięć procesu nadal pokazuje poprzedni wskaźnik.
- `readActive()` nie udostępnia quarantined rekordu jako ACTIVE; potwierdzony retry/invalidacja usuwa odpowiedni wpis quarantine.
- `MainActivity.buildMasterEvidenceContext()` sprawdza read-only `hasPendingWorkspaceTransaction()` przed odczytem active pointera. Pending swap journal/receipt albo błąd kontroli blokuje wybór dowodów. Sprawdzenie nie wykonuje recovery poza `ProjectWorkspaceOperationLock`.
- Host fault matrix 17/17; compile shim PASS; commit gate 9/9; coordinator 19/19; policy 11/11; protocol 8/8; concurrency 3/3; filesystem integration 9/9; workspace lock 5/5; workspace transaction 12/12; resolver 73/73 = 166 hostowych PASS. MainActivity static guards PASS.
- Jeden test Robolectric dodany, NOT RUN. Gradle/Robolectric/Android SharedPreferences/Android compile/APK/device/process-death NOT RUN.
- Granica: nadal nie znamy z samego `commit()==false`, które bajty są na dysku. Quarantine chroni bieżący proces, a pending workspace transaction gate chroni wybór dowodów do czasu reconciliation; to nie jest uniwersalny durable journal.
- Następny krok: uzyskać rzeczywiste środowisko Android i zweryfikować adapter SharedPreferences oraz startup recovery po odtworzeniu procesu; dopiero potem rozważyć trwały journal dla ogólnych preferencji. Companion transport BLOCKED.


## Aktualizacja 0.6.123 — atomic startup evidence gate
- Audyt wykrył TOCTOU: osobny `hasPendingWorkspaceTransaction()` i `readActive()` mogły rozminąć się z rozpoczęciem patch/import/recovery.
- Dodano `HostActiveEvidenceStartupGate` z jawnie testowanymi stanami CLEAR/PENDING/UNKNOWN oraz leniwym odczytem active pointer. PENDING/UNKNOWN nie wywołują odczytu; wyjątki fail-closed.
- `buildMasterEvidenceContext()` wykonuje pending probe, workspace identity i active pointer read pod jednym `ProjectWorkspaceOperationLock.tryWithLock`; lock contention blokuje dowody bez oczekiwania.
- Wynik regresji: 170 hostowych PASS + MainActivity atomic-lock static guard PASS. Cztery testy JUnit dodane, NOT RUN.
- Android preflight BLOCKED (brak wrapper JAR, Gradle, SDK; DNS distribution unavailable); Android/Robolectric/APK/process-death NOT RUN. App 0.6.54/code 80; Companion Transport BLOCKED.
- Następny etap: prawdziwe testy Android SharedPreferences i startup/process recreation po przywróceniu środowiska Gradle/SDK; utrzymać wspólną blokadę na każdej ścieżce workspace mutation.


## Aktualizacja 0.6.124 — workspace failure reconciliation lock coverage
- Audyt wykrył drugi TOCTOU w ścieżce błędu apply: `hasPendingWorkspaceTransaction()` było odczytywane poza wspólną blokadą, a dopiero później zapisywano REJECTED lub unieważniano active pointer. Teraz pending probe, warunkowy ledger transition i pointer invalidation są atomowe pod `ProjectWorkspaceOperationLock`.
- Diagnostyczny odczyt workspace identity + active pointer po startup recovery został połączony w jedną sekcję `ProjectWorkspaceOperationLock.withLock` na wątku recovery, aby log nie łączył wartości z różnych generacji workspace. Błąd tego odczytu jest jawnie raportowany jako stan niepotwierdzony, a nie jako zmiana zakresu.
- Dodatkowo normalny `acknowledgePatchReceipt()` po apply jest teraz wykonywany pod wspólną blokadą po ponownym sprawdzeniu identity patcha. Import lub nowsza generacja workspace nie może więc wejść między aktywację a usunięcie starego receipt.
- Rozszerzono hostowy lock harness o dwa scenariusze współbieżności i MainActivity static guard o wszystkie trzy punkty integracji: failure reconciliation, post-recovery diagnostic i normal receipt acknowledgement. Regresja: 172 hostowe przypadki PASS; compile shim/static guards PASS.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK oraz niedostępny DNS `services.gradle.org`; Gradle/Robolectric/Android/APK/device/process-death NOT RUN. App 0.6.54/code 80; Companion Transport BLOCKED.
- Następny krok: kontynuować audyt wszystkich wejść do `ProjectWorkspaceStore` i sprawdzić, czy każda mutacja oraz odczyt używany do decyzji są objęte wspólną blokadą; osobno przygotować drogę do prawdziwych testów Android, bez zastępowania ich shimami.


## Aktualizacja 0.6.125 — publiczna granica blokady ProjectTestRunner
- `ProjectTestRunner.verifyAppliedChange()` pobiera teraz globalną blokadę samodzielnie; wszystkie odczyty integrity/snapshot/changed paths należą do jednej sekcji krytycznej. Implementacja bez ponownego locka jest prywatna.
- Reentrantność zachowuje zgodność z MainActivity, która już wywołuje runnera pod blokadą.
- Dodano JUnit concurrency test (ADDED / NOT RUN), hostowy test reentrant `tryWithLock` i source guard publicznej granicy runnera.
- Regresja: 173 hostowe przypadki PASS; compile shim i statyczne guardy PASS. Android/Gradle/Robolectric/APK/device/process-death NOT RUN; preflight BLOCKED.
- Następny krok: zautomatyzować audyt wszystkich produkcyjnych call sites `ProjectWorkspaceStore`; dla każdego wskazać wspólną blokadę i odrzucać nowe niechronione wejścia. Nie dodawać blokady wewnątrz `@Synchronized` metod bez analizy kolejności locków.


## Aktualizacja 0.6.126 — pełny audyt call sites + trwały source guard
- Zinwentaryzowano 39 produkcyjnych wywołań API `ProjectWorkspaceStore` w `MainActivity.kt`: 37 bezpośrednio wewnątrz `ProjectWorkspaceOperationLock.withLock`/`tryWithLock`; 2 callbacki (`currentWorkspaceIdentity`, `acknowledgePatchReceipt`) są wykonywane przez `PatchReceiptRecoveryCommitGate.commit()`, który sam pobiera globalną blokadę.
- Store jest konstruowany produkcyjnie tylko w MainActivity. Jedyny drugi produkcyjny konsument API to `ProjectTestRunner`, którego publiczna metoda `verifyAppliedChange()` samodzielnie pobiera blokadę. Pozostałe odwołania produkcyjne korzystają z typów danych lub czystych analizatorów, nie z dodatkowych instancji store.
- Dodano `tools/workspace_operation_lock_harness/WorkspaceCallSiteGuard.py`: parser strukturalny Kotlin pomija komentarze i stringi, liczy call sites i odrzuca każde nowe/niechronione wejście, dopóki nie zostanie jawnie sklasyfikowane. Guard potwierdza również blokadę commit gate i granicę runnera.
- Regresja hostowa: 173/173 PASS; compile shim `-Werror` PASS; runner/MainActivity/new call-site source guards PASS. Wszystkie harnessy uruchomiono osobno. Static guards nie są liczone jako przypadki testowe.
- Android preflight ponownie BLOCKED: Java 21 dostępna, ale brak `gradle-wrapper.jar`, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/JUnit/Robolectric/Android SharedPreferences/APK/device/process-death NOT RUN.
- Nie zmieniono produkcyjnego kodu Kotlin ani wersji aplikacji (`0.6.54 / code 80`). Companion Transport pozostaje BLOCKED.
- Następny etap 0.6.127: audyt kolejności blokad i punktów wejścia chat-switch / MainActivity lifecycle; sprawdzić, czy lock nie jest trzymany podczas operacji UI, I/O sieciowego lub długiego zapisu, oraz dodać testy deadlock/starvation/fairness. Równolegle znaleźć legalną drogę do rzeczywistego Gradle + Android SDK/Robolectric bez zastępowania ich shimami.

## Aktualizacja 0.6.127 — chat-switch/recovery lock serialization
- Audyt wykazał, że zmiana czatu unieważniała aktywny ChangeSet pointer pod monitorem MainActivity, ale bez wspólnej blokady workspace. Startup receipt recovery mógł wykonywać aktywację/ACK pod workspace gate w tym samym czasie. Exact-chat resolver blokował użycie obcego scope, lecz sama mutacja persistent pointera nie była serializowana.
- `invalidateActiveEvidenceScopeForChatSwitch()` pobiera teraz `ProjectWorkspaceOperationLock.withLock`; zachowano kolejność Activity monitor -> workspace gate. `isCurrentChat()` pozostaje monitor-free, aby uniknąć odwrócenia kolejności blokad.
- Dodano hostowy scenariusz serializacji recovery commit vs chat-switch invalidation i trwały source guard. Pełna regresja: 174 hostowe PASS; workspace lock 9/9; recovery concurrency 3/3; workspace call-site guard PASS.
- Audyt lock scope nie wykazał wywołań sieciowych ani dispatchu UI wewnątrz workspace gate. Import nadal konsumuje URI stream pod blokadą, ponieważ obecny importer łączy ekstrakcję i swap; oddzielenie wymaga nowego staged-import API z ponowną walidacją przed commit i pozostaje osobnym zadaniem.
- Android preflight BLOCKED (brak wrapper JAR, Gradle, SDK; DNS distribution unavailable); Android/Robolectric/APK/device/process-death NOT RUN. App 0.6.54/code 80; Companion Transport BLOCKED.
- Następny krok: pełny lock-order/lifecycle audit, fairness semantyki `tryWithLock` oraz projekt staged importu bez osłabienia atomowości; równolegle realne środowisko Gradle + Android SDK.

## Aktualizacja 0.6.128 — fairness non-blocking workspace probe
- Audyt API `ReentrantLock` wykazał, że untimed `tryLock()` może ominąć kolejkę oczekujących nawet przy `ReentrantLock(true)`. `ProjectWorkspaceOperationLock.tryWithLock()` używa teraz `tryLock(0L, TimeUnit.NANOSECONDS)`, czyli zero-wait overload respektującego fair queue.
- Interrupted caller otrzymuje `null`, nie wykonuje operacji i zachowuje flagę interrupt. Dodano test runtime i source guard.
- Wynik: workspace lock 10/10; pełna regresja 175 hostowych PASS; compile shim i source guards PASS.
- ZIP import pozostaje pod wspólną blokadą. Bezpieczne skrócenie wymaga staged prepare/commit API, immutable staging ownership, walidacji workspace identity/integrity tuż przed commit, cleanup i recovery po process death. Nie wykonano ryzykownego refaktoru.
- Android preflight BLOCKED (brak wrapper JAR, Gradle, SDK i DNS do services.gradle.org); Android/Robolectric/APK/device/process-death NOT RUN. App 0.6.54/code 80; Companion Transport BLOCKED.
- Następny etap 0.6.129: zaprojektować i przetestować protokół staged importu, szczególnie TOCTOU, staging ownership, awarie i recovery; dopiero potem rozważyć przeniesienie ekstrakcji poza globalną blokadę.

## Aktualizacja 0.6.129 — staged import: kontrakt przed refaktorem
- Zbadano rzeczywistą ścieżkę `ProjectWorkspaceStore.importZip()`: ekstrakcja, manifest, integrity check, snapshot i journaled swap są dziś jedną metodą `@Synchronized`; MainActivity obejmuje import oraz evidence invalidation wspólnym workspace lockiem.
- Nie przeniesiono ekstrakcji poza lock. Bez prepare/commit tokena i commit-time rewalidacji byłoby to ryzyko TOCTOU; dodatkowo trzeba zachować kolejność locków store monitor/workspace gate.
- Dodano `STAGED_IMPORT_PROTOCOL_DESIGN_0.6.129_PL.md` oraz 12-case hostowy model kontraktu. Jawna semantyka: jeżeli workspace zmieni się po prepare, commit może go zastąpić tylko po ponownej weryfikacji aktualnego stanu i snapshotowaniu właśnie tej najnowszej wersji; uszkodzony workspace blokuje import.
- Produkcyjny importer pozostaje bez zmian. Source guard pilnuje, aby import i evidence invalidation pozostały w jednej sekcji krytycznej, dopóki pełny protokół nie zostanie wdrożony.
- Regresja: 187 hostowych PASS; compile shim i source guards PASS. Android/Gradle/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED). App 0.6.54/code 80; Companion Transport BLOCKED.
- Następny krok 0.6.130: zaprojektować trwały format metadanych prepared stagingu i protokół cleanup/recovery, a następnie testy fault injection dla granic journal/snapshot/rename/evidence invalidation. Dopiero po zamknięciu tej macierzy wdrożyć API produkcyjne.


## Aktualizacja 0.6.130 — staged import durable lifecycle/restart model
- Rozszerzono hostowy kontrakt staged importu z 12 do 20 przypadków. Nowy model jawnie rozróżnia PREPARED, COMMITTING, SWAPPED_AWAITING_EVIDENCE, COMMITTED i ABORTED oraz wynik swapu NONE/OLD_CURRENT/NEW_CURRENT.
- Dodano scenariusze restartu: orphan cleanup, commit bez swapu, stary current, wykryty nowy current, invalidacja evidence przed finalnym COMMITTED, idempotentne recovery i terminalność.
- Dodano `STAGED_IMPORT_DURABLE_RECOVERY_MODEL_0.6.130_PL.md`. Model jest in-memory; nie stanowi testu crash consistency prawdziwego filesystemu.
- Pełna regresja: 195 hostowych przypadków PASS; compile shim i source guards PASS. Android/Gradle/Robolectric/APK/device/process-death NOT RUN; preflight BLOCKED.
- Produkcyjny importer bez zmian i nadal pod global lockiem; app `0.6.54 / code 80`; Companion Transport BLOCKED.
- Następny krok 0.6.131: zaprojektować format/versioning journalu i crash-consistent zapis z wykorzystaniem istniejącego swap journal, z fault injection na każdej granicy zapisu/rename/recovery. Nie wprowadzać drugiego niezależnego źródła prawdy.


## Aktualizacja 0.6.131 — hostowy filesystem journal prototype
- Dodano niezależny hostowy prototyp plikowego journalu: wersja formatu, bounded ID, allowlista stanów, SHA-256 payloadu, parser fail-closed, limit 4096 B, temp file + `FileChannel.force(true)` + `ATOMIC_MOVE`; brak atomowego rename odrzuca zapis bez fallbacku. Directory flush jest best-effort.
- Fault matrix hostowa 8/8 PASS: round-trip, atomowa podmiana, wyjątek przed rename zachowuje poprzedni rekord, checksum corruption, truncation, unknown version/state i unsafe ID.
- Pełna regresja: 203 hostowe PASS; compile shim i source guards PASS. Android/Gradle/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED).
- Prototyp nie jest zintegrowany z produkcyjnym `ProjectWorkspaceStore`; nie tworzy drugiego źródła prawdy. Import nadal pod global lockiem. `force(true)`/`ATOMIC_MOVE` na hoście nie dowodzą trwałości Android/power-loss. App 0.6.54/code 80; Companion Transport BLOCKED.
- Następny krok 0.6.132: audyt istniejącego swap journal, manifestów i startup recovery; mapowanie prototypu na istniejący journal, aby uniknąć dwóch źródeł prawdy. Dodać testy zgodności i awarii na granicach obecnego protokołu, zanim powstanie jakakolwiek integracja produkcyjna.

## Aktualizacja 0.6.132 — produkcyjny swap journal bounds
- Audyt objął rzeczywisty binarny `workspace.swap.journal` (AISW v1/v2), zapis temp + `fd.sync()` + `ATOMIC_MOVE`, recovery przez hash manifestu, integrity całego drzewa i odtwarzanie patch receipt.
- Wykryto brak jawnego limitu rozmiaru pliku przed parsowaniem. `readSwapJournal()` wymaga teraz zwykłego pliku bez symlinka/path escape i rozmiaru `1..16 KiB` przed `FileInputStream`.
- Zachowano kompatybilność AISW v1/v2 i odrzucanie trailing bytes; format i writer nie zostały zmienione.
- Dodano trzy testy JUnit (oversized, trailing bytes, legacy v1) — ADDED / NOT RUN. Source guard produkcyjnego readera PASS.
- Istniejąca regresja hostowa: 203 PASS; compile shim i guards PASS. Android/Gradle/JUnit/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED).
- Nie wdrożono staged importu. Import nadal pod global lock; hostowy prototyp 0.6.131 nie został scalony z produkcyjnym journalem. Directory fsync nadal best-effort.
- Następny etap 0.6.133: fault matrix dla stale temp files, awarii między force/rename/directory sync oraz recovery cleanup. Nie zmieniać formatu journalu bez planu migracji i testów kompatybilności.

## Aktualizacja 0.6.133 — verified orphan previous cleanup
- Wykryto, że po odzyskaniu zatwierdzonego `current/` mogły pozostać osierocone `.previous-*` artefakty z innych/przerwanych operacji.
- `cleanupInterruptedStaging()` weryfikuje teraz cały current tree względem manifestu i dopiero wtedy usuwa osierocone previous directories/manifests. Symlinki są odpinane jako wpisy; cleanup nie dereferuje celów. Nieprawidłowy current oznacza zachowanie backupów.
- Rzeczywisty hostowy `ProjectWorkspaceStore.kt` przeszedł 13/13 scenariuszy recovery; pełna regresja 204 PASS; source guards i compile shim PASS. JUnit analogiczny test dodany, ale NOT RUN.
- Nie zmieniono formatu AISW v1/v2 ani commit point. Staged import nadal nie jest produkcyjnie zintegrowany; ZIP import pozostaje pod global lockiem.
- Android/Gradle/JUnit/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED); app `0.6.54 / code 80`; Companion Transport BLOCKED.
- Następny krok 0.6.134: zbadać stale/incomplete temp files writerów swap journal i patch receipt, ich ownership, kolizje i cleanup po restarcie; nie wykonywać nieatomowego fallbacku.

## Aktualizacja 0.6.134 — fail-closed cleanup interrupted temp evidence
- Test regresyjny ujawnił, że `cleanupInterruptedStaging()` usuwał temp/staging artifacts przed sprawdzeniem integralności current. Sama obecność `current/` i `current.manifest` nie jest dowodem zatwierdzonego workspace.
- Zmieniono kolejność: najpierw pełne `verifyTreeAgainstManifest`; przy negatywnym wyniku nie usuwa się żadnych temp/staging/receipt/previous artifacts. Dopiero zweryfikowany current autoryzuje cleanup. Symlink jest usuwany jako wpis bez dereferencji.
- Rzeczywisty `ProjectWorkspaceStore.kt` host recovery harness 15/15; pełna regresja 206 PASS; compile shim i source guards PASS. JUnit test ADDED / NOT RUN.
- Nie zmieniono formatu AISW, writer, commit point ani lock scope. Android/Gradle/Robolectric/APK/device/process-death NOT RUN; app 0.6.54/code 80; Companion Transport BLOCKED.
- Następny etap 0.6.135: fault injection i audyt ownership temp files w writerach swap journal oraz patch receipt, w tym nieudany cleanup/rename i kolizje identyfikatorów. Zachować fail-closed i nie zmieniać atomowego commit protocol bez dowodów.

## Aktualizacja 0.6.135 — ownership-scoped temp writer cleanup
- Audyt produkcyjnych writerów wykrył, że bezwarunkowe `temp.delete()` w finally mogło usunąć pre-existing path po nieudanym `createNewFile()`. W `writeSwapJournal()` i `writePatchReceipt()` dodano `ownsTemp`, ustawiane wyłącznie po udanym CREATE_NEW; cleanup dotyczy tylko ścieżki przejętej przez bieżący writer.
- `cleanupOwnedTemporaryFile()` używa `Files.deleteIfExists()` (unlink wpisu, bez dereferencji symlink targetu), a cleanup pozostaje best-effort, żeby nie maskować pierwotnego błędu.
- Dodano 4 hostowe testy ownership w harnessie uruchamiającym rzeczywisty `ProjectWorkspaceStore.kt`; wynik 19/19. Dwa testy JUnit ADDED/NOT RUN. Writer ownership source guard PASS.
- Pełna regresja: 210 hostowych PASS; compile shim i wszystkie source guards PASS. Android preflight BLOCKED (brak wrapper JAR, Gradle, SDK; DNS niedostępny); Android/JUnit/Robolectric/APK/device/process-death NOT RUN.
- Nie zmieniono AISW/receipt format, commit point, ATOMIC_MOVE ani directory fsync policy. Staged importer nadal nie jest produkcyjnie wdrożony, import ZIP pod global lock. App `0.6.54 / code 80`; Companion Transport BLOCKED.
- Następny krok 0.6.136: dodać kontrolowany fault seam do testowania writerów na granicach zapisu, flush, `fd.sync`, atomic move i directory sync; sprawdzić stale receipt temp i cleanup failure. Nie używać hostowych testów jako dowodu Android power-loss durability.


## Aktualizacja 0.6.136 — durable writer fault injection
- Wspólny `publishWorkspaceDurableFile()` obsługuje oba rzeczywiste writery: swap journal i patch receipt. Seamy testowe obejmują `flushAndSync`, `moveAtomically` oraz `syncDirectory`, przekazywane per wywołanie; brak globalnego mutable state.
- Host fault matrix 28/28 dla rzeczywistego `ProjectWorkspaceStore.kt`; pełna regresja 219 PASS / 12 harnessów. Obejmuje kolizję CREATE_NEW, write/flush/fd.sync errors, atomic move failure, brak ATOMIC_MOVE, directory sync error i kolejność publikacji.
- Directory fsync nadal best-effort; nieatomowy fallback zabroniony. Bez zmian formatu AISW/receipt, commit point, public API i lock scope.
- Cztery JUnit testy są ADDED / NOT RUN. Android preflight BLOCKED; brak Gradle, wrapper JAR i SDK; APK nie zbudowano. App 0.6.54/code 80; Companion Transport BLOCKED.
- Następny krok 0.6.137: audyt recovery po awarii na granicy „atomic move zakończony, directory sync pominięty/odrzucony” oraz zachowania receipt temp po restartach; najpierw określić, które stany da się odtworzyć na istniejącym journalu bez zmiany protokołu. Dodać testy idempotencji i konfliktów; nie przedstawiać hostowych testów jako Android power-loss proof.


## Bieżący stan prac — 0.6.139 (2026-11-07)

Priorytetem jest domknięcie niezawodności magazynu profili przed powrotem do historii rozmów. W `SecureStore.saveConfig()` wdrożono zachowanie istniejącego ciphertextu przy pustym polu klucza API, po sprawdzeniu czy sekret jest czytelny. Cztery testy awarii i regresji dodano na roboczej gałęzi `work/0.6.139-saveconfig-secret-preservation`; testy są ADDED / NOT RUN. CI dla poprzedniego SHA `30e0f496...` nadal wykonuje testy i kompilację, więc nowego commita nie wysyłamy na gałąź audytową przed zakończeniem tego runu. Po walidacji 0.6.139 wracamy do odporności zapisu/odtwarzania historii rozmów. App source version pozostaje 0.6.54 / versionCode 80; nie utworzono APK, Companion pozostaje nieaktywny.

### 0.6.139 — potwierdzony błąd migracji starego mirror historii

W trakcie przeglądu startowego restoreMessages() wykryto i naprawiono ryzyko odtworzenia nieaktualnej rozmowy z `chat_messages`, gdy kanoniczne `chats_v2` istnieje, ale celowo zawiera pustą listę rozmów (np. po usunięciu ostatniej rozmowy). Migracja legacy jest teraz dozwolona wyłącznie przy braku kanonicznego dokumentu; niepewny odczyt blokuje restore. Dwa testy dodane, ADDED / NOT RUN. CI 42411603439 nadal waliduje starszy SHA 30e0f496..., więc poprawka nie jest jeszcze zweryfikowana.

### 0.6.139 — atomowe zapisy historii rozmów

Audyt wykazał, że MainActivity używała sekwencji osobnych read/write, podczas gdy archiwa odpowiedzi i raportów agentów wykonywały atomowe updateRoot. Przeniesiono siedem mutujących ścieżek MainActivity do ChatHistoryStore.updateRoot, aby wszystkie zapisy historii współdzieliły ten sam lock read-modify-write. Dodano test współbieżności wiadomość-versus-aktywność. Produkcja: 49da9dcb..., 84c495ec...; test: b4193751...; ADDED / NOT RUN. CI 42411603439 nadal nie obejmuje nowych commitów.

### 0.6.139 — wspólny lock dla zapisów historii i replayów archiwów

Wszystkie siedem mutujących ścieżek MainActivity zostało przeniesionych na ChatHistoryStore.updateRoot(), wspólny z replayem archiwów odpowiedzi i raportów. Statyczny przegląd potwierdził 0 bezpośrednich wywołań writeRoot w MainActivity. Dodano test równoległego dopisania wiadomości i agentActivity. Test ADDED / NOT RUN; walidacja CI dla nowych zmian oczekuje na zwolnienie aktywnego runu.

### 0.6.139 — awarie replayu archiwów odpowiedzi i raportów

Dodano scenariusze commit failure przed i po apply dla replayu ChatResponseArchive i TaskResultArchive. Testy sprawdzają zachowanie archiwum jako źródła odzyskiwania, idempotencję po restarcie i brak duplikacji, gdy apply zmienił już widoczny snapshot. Cztery testy + fixture dodane, NOT RUN.

### 0.6.139 — odporność zapisu archiwum odpowiedzi MASTER

Dodano test awarii niejednoznacznego commit w ChatResponseArchive: po apply + zwróceniu false magazyn musi wejść w kwarantannę, nie udawać czytelności i blokować następne zapisy. Commit testowy e08735883591841b645202f33e72d307f809f1b5; ADDED / NOT RUN. CI nadal waliduje starszy SHA 30e0f496...

### 0.6.139 — jawne przerwanie replayu przy kwarantannie archiwum

Rozszerzono testy obu archiwów: po niejednoznacznym commit `restoreArchivedResponses()` i `restoreArchivedReports()` muszą przerwać działanie, a nie zwrócić pustego wyniku odzyskiwania. Commity testowe: `5eb52b62...`, `5fbfa126...`; ADDED / NOT RUN. CI 42411603439 nadal dotyczy starszego SHA `30e0f496...`.

### 0.6.139 — testy dostępu i serializacji archiwum odpowiedzi

Dodano dwa testy ChatResponseArchive: błąd dostępu do preferencji nie może wyglądać jak puste archiwum; zapis musi respektować blokadę całego read-modify-write. Commit `bccb390d...`; ADDED / NOT RUN. CI 42411603439 nadal dotyczy SHA `30e0f496...`.

### 0.6.139 — poprawka kontraktu błędu zapisu archiwów

Wykryto i poprawiono propagowanie wyjątku z dostępu do SharedPreferences w `ChatResponseArchive.put()` i `TaskResultArchive.put()`. Oba API zwracają teraz `false` przy błędzie dostępu, pozostawiając dane nietknięte. Testy regresji istnieją dla obu archiwów; ADDED / NOT RUN. Commity: `d8223a7b...`, `9692a908...`.

Testy współbieżności obu archiwów doprecyzowano: asercja potwierdza rzeczywiste oczekiwanie wątku na monitorze (`BLOCKED`), a nie tylko brak zakończenia w krótkim oknie czasowym. Commity `b14f6ff8...` i `808555f1...`; ADDED / NOT RUN.

### 0.6.139 — jawne błędy odzyskiwania i cache po commicie

Dodano logowanie awarii odtwarzania raportów agentów (`AIMasterRecovery`) i przesunięto aktualizację lokalnego cache tytułu rozmowy na czas po udanym commicie. Commit `ab954ce9...`. Przegląd statyczny potwierdzony; test/build NOT RUN.

### 0.6.139 — domknięcie blokady ChatHistoryStore.writeRoot

Bezpośredni `writeRoot()` obejmuje teraz jedną wspólną sekcją krytyczną walidację, kontrolę istniejącego dokumentu i commit. Dodano test potwierdzający blokowanie zapisu pod blokadą `chat_state`. Commity `f9d566f9...` i `4028df55...`; test ADDED / NOT RUN.
