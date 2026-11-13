# AKTUALNY STAN — 5 listopada 2026

- Źródła aplikacji: `versionName=0.6.54`, `versionCode=80`.
- Najnowsza paczka robocza: `0.6.56-SOURCE` (audyt integracji Companion pkt 4; bez zmian produkcyjnego kodu i bez nowego APK).
- Poprzednia paczka: `0.6.55-SOURCE` (utwardzony szkic rejestru połączeń).
- Rejestr: `PACKAGE_REGISTRY_PL.md`.
- Kompilacja Gradle i JUnit dla bieżących źródeł: NOT RUN; wrapper nie może pobrać Gradle 8.13 z powodu błędu DNS `services.gradle.org`.
- Sam szkic `CompanionConnectionRegistry.kt` przeszedł `kotlinc -Werror`, lokalny harness 13/13 oraz niezależny smoke test 1000 wyścigów; nie jest częścią buildu Android.
- Pkt 4 pozostaje projektem/szkicem. Nie deklarować natychmiastowego zamykania połączeń w działającej aplikacji.
- Audyt 0.6.56 wykrył dodatkowe ryzyka: ticket musi przejść przez handshake, brak przejścia ownera do FAILED po zamknięciu socketu, możliwy lock inversion callbacku store, obsługa błędu Keystore po wipe commit, współdzielony timeout accept i porzucone receipt-y TLS.
- Szczegóły: `BUILD_STATUS_0.6.56_PL.md`, `docs/COMPANION_PKT4_INTEGRATION_REVIEW_0.6.56_PL.md`.

---

# AI MASTER PRO — historia i stan projektu

- 0.6.19: weryfikacja manifestów workspace/snapshotu, porównanie rzeczywistego zakresu patcha i transakcyjny rollback.
- 0.6.19.1: utwardzenie bramki zaufania — ręczny/historyczny `PASS / PROJECT_TESTS` nie odblokowuje `APPLIED`.
- 0.6.20: wersjonowany kontrakt bundle/request/receipt dla przyszłego desktop executora, zamknięta lista zadań, limity i weryfikacja podpisu.
- 0.6.21: eksport wyłącznie zweryfikowanego snapshotu do ZIP bundle, binarny manifest i ponowna kontrola SHA-256 archiwum.
- 0.6.22: skan zawartości i nazw plików przed eksportem; wykryte sekrety blokują paczkę, a raport nie ujawnia wartości.
- 0.6.23: kontrakt parowania companion (dwukierunkowe challenge-response HMAC, nonce, TTL, replay protection) oraz poprawki skanowania UTF-16 i ograniczonego odczytu ZIP.
- 0.6.24: uwierzytelnione ramki komunikacyjne HMAC-SHA256, odrębne klucze dla obu kierunków, SHA-256 payloadu, wiązanie kierunku, ścisła kolejność i ochrona przed replay, limit 1 MiB na ramkę.
- 0.6.25: ograniczony binarny kodek ramek `AIMF` — limity wejścia przed alokacją, wersjonowanie, ścisły UTF-8, allowlista typów/kierunków, kanoniczny Base64URL, odrzucanie nadmiarowych bajtów. Kodek nie uwierzytelnia samodzielnie ramki; nadal wymagana jest weryfikacja HMAC.
- 0.6.26: utwardzenie ruchu HTTP — domyślnie zablokowany cleartext, wyjątki wyłącznie dla `localhost`, `127.0.0.1` i aliasu emulatora `10.0.2.2`; klient API odrzuca HTTP w sieci LAN i HTTP z kluczem API, a przekierowania HTTP nie są automatycznie śledzone.
- 0.6.27: ograniczony odczyt odpowiedzi dostawców — strumieniowy limit 4 MiB, jawna obsługa zbyt dużej odpowiedzi i niepoprawnego UTF-8. Naprawia nieograniczony `readText()` wykryty w audycie 0.6.26.
- 0.6.28: fail-closed walidacja endpointów dostawców i projekt bezpieczeństwa Companion TLS/mTLS.
- 0.6.29: naprawa DoS w parowaniu — niepoprawny proof nie zużywa challenge; porównanie uwierzytelniaczy stałoczasowe; rozszerzony kontrakt maszyny stanów sesji i transferu.
- 0.6.30: odbiornik ramek Companion traktuje uwierzytelnione `CLOSE` jako stan terminalny, odrzuca każdą późniejszą ramkę, udostępnia jawne unieważnienie odbiornika i best-effort zeruje własną kopię klucza; uwierzytelnione wyczerpanie numeracji również zamyka odbiornik.
- 0.6.31: dodano niezależną, testowalną politykę timeoutów opartą na zegarze monotonicznym: handshake, bezczynność, pojedynczy transfer i twardy maksymalny czas sesji. Aktywność może odświeżyć tylko idle timeout — nigdy hard lifetime.
- 0.6.32: poprawiono spójność czasową przejść polityki timeoutów: każda operacja pobiera dokładnie jedną próbkę zegara i używa jej zarówno do sprawdzenia deadline, jak i zapisania znacznika czasu. Zapobiega to odświeżeniu aktywności po deadline wskutek drugiego odczytu zegara. Polityka nadal nie uwierzytelnia peerów i nie jest podłączona do transportu.
- 0.6.33: testami potwierdzono atomowość odbiornika przy współbieżnym replay oraz niezależność jego kopii klucza od późniejszych zmian tablicy wejściowej.
- 0.6.34: odbiornik sam egzekwuje kanoniczny Base64URL, nawet gdy wywołujący omija kodek binarny; alternatywne kodowanie tych samych bajtów jest odrzucane bez zużycia sekwencji.
- 0.6.35: TTL parowania korzysta z zegara monotonicznego wybieranego przez instancję authority; publiczne metody nie przyjmują już parametru czasu od wywołującego, co eliminuje ryzyko przypadkowego cofania lub mieszania źródeł czasu.
- 0.6.36: jednorazowy receipt wydawany i konsumowany przez tę samą instancję authority; samo `accepted=true` nie stanowi dowodu parowania.
- 0.6.37: dodano `CompanionSessionOwner`, który konsumuje receipt, wyprowadza klucz odbiorczy, spina receiver z timeoutami i egzekwuje terminalne stany sesji. `ACTIVE` oznacza zweryfikowane parowanie PSK, a nie uwierzytelnienie TLS/mTLS.
- 0.6.38: dodano platformowy `CompanionTlsContext` z X.509 KeyManager/TrustManager, HTTPS hostname verification i wymogiem certyfikatu klienta po stronie serwera.
- 0.6.39: wykonano rzeczywiste testy handshake mTLS na loopback: sukces dla zaufanych certyfikatów, TLS 1.2, odrzucenie braku certyfikatu klienta, niezaufanego CA i błędnego SAN; 61/61 testów PASS, 10 powtórzeń 610/610.
- Recenzja patchy między agentami korzysta z istniejącego `TeamRunEngine` i nie jest dublowana.
- Kod importowanego projektu nie jest uruchamiany, dopóki nie zostanie wdrożony rzeczywisty sandbox procesu z egzekwowanymi limitami CPU/RAM/czasu/sieci.

## Dokumentacja
- `BUILD_STATUS_0.6.39_PL.md`
- `CHANGELOG_0.6.39_COMPANION_MTLS_HANDSHAKE_TESTS_PL.md`
- `BUILD_STATUS_0.6.38_PL.md`
- `CHANGELOG_0.6.38_COMPANION_PLATFORM_MTLS_CONTEXT_PL.md`
- `BUILD_STATUS_0.6.37_PL.md`
- `CHANGELOG_0.6.37_COMPANION_SESSION_OWNER_PL.md`
- `BUILD_STATUS_0.6.36_PL.md`
- `CHANGELOG_0.6.36_PAIRING_RECEIPT_CAPABILITY_PL.md`
- `BUILD_STATUS_0.6.35_PL.md`
- `CHANGELOG_0.6.35_PAIRING_CLOCK_OWNERSHIP_PL.md`
- `BUILD_STATUS_0.6.34_PL.md`
- `CHANGELOG_0.6.34_RECEIVER_CANONICAL_BASE64_PL.md`
- `BUILD_STATUS_0.6.33_PL.md`
- `CHANGELOG_0.6.33_COMPANION_RECEIVER_CONCURRENCY_PL.md`
- `BUILD_STATUS_0.6.31_PL.md`
- `COMPANION_TRANSPORT_SECURITY_DESIGN_0.6.32_PL.md`
- `CHANGELOG_0.6.31_COMPANION_TIMEOUT_POLICY_PL.md`
- `BUILD_STATUS_0.6.30_PL.md`
- `CHANGELOG_0.6.30_COMPANION_RECEIVER_TERMINAL_CLOSE_PL.md`
- `COMPANION_TRANSPORT_SECURITY_DESIGN_0.6.32_PL.md`
- `BUILD_STATUS_0.6.29_PL.md`
- `CHANGELOG_0.6.29_PAIRING_DOS_HARDENING_PL.md`
- `COMPANION_TRANSPORT_SECURITY_DESIGN_0.6.32_PL.md`
- `BUILD_STATUS_0.6.27_PL.md`
- `CHANGELOG_0.6.27_BOUNDED_PROVIDER_RESPONSES_PL.md`
- `BUILD_STATUS_0.6.26_PL.md`
- `CHANGELOG_0.6.26_CLEARTEXT_HARDENING_PL.md`
- `BUILD_STATUS_0.6.25_PL.md`
- `CHANGELOG_0.6.25_BOUNDED_FRAME_CODEC_PL.md`
- `BUILD_STATUS_0.6.24_PL.md`
- `CHANGELOG_0.6.24_AUTHENTICATED_FRAMES_PL.md`
- `BUILD_STATUS_0.6.23_PL.md`
- `CHANGELOG_0.6.23_LOCAL_PAIRING_PROTOCOL_PL.md`
- `BUILD_STATUS_0.6.22_PL.md`
- `CHANGELOG_0.6.22_BUNDLE_SECRET_SCAN_PL.md`
- `CHANGELOG_0.6.20_EXECUTOR_PROTOCOL_PL.md`
- `docs/EXECUTOR_PROTOCOL_0.6.20_PL.md`
- `PROJECT_WORKSPACE_ROADMAP_PL.md`

- `BUILD_STATUS_0.6.31_PL.md`
- `CHANGELOG_0.6.31_COMPANION_TIMEOUT_POLICY_PL.md`
- `COMPANION_TRANSPORT_SECURITY_DESIGN_0.6.32_PL.md`


## Historyczny stan prac — 0.6.39 (archiwalny; nie opisuje bieżącej wersji)

Pozycje 1–3 Companion są ukończone. W 0.6.38 dodano platformowy fundament TLS/mTLS, a w 0.6.39 potwierdzono rzeczywisty handshake testowy na loopback i podstawowe przypadki negatywne. Pozycja 4 nadal jest częściowa: brak bezpiecznego provisioningu, SPKI pinningu, rotacji/unieważniania i powiązania peer identity z właścicielem sesji. Szczegóły i kolejność znajdują się w `PROJECT_WORKSPACE_ROADMAP_PL.md`, `CHANGELOG_0.6.39_COMPANION_MTLS_HANDSHAKE_TESTS_PL.md` i `BUILD_STATUS_0.6.39_PL.md`.


## Wydanie 0.6.38 — TLS/mTLS Companion (fundament)
Dodano platformowy `CompanionTlsContext`: standardowe X.509 KeyManager/TrustManager, klient z HTTPS endpoint identification i serwer wymagający certyfikatu klienta. Dozwolone TLS 1.2/1.3, fail-closed. To nie jest jeszcze działający transport ani kompletne mTLS: brak provisioningu certyfikatów, pinning/rotacji, testów handshake obu stron i integracji sesji. Szczegóły: `CHANGELOG_0.6.38_COMPANION_PLATFORM_MTLS_CONTEXT_PL.md` oraz `BUILD_STATUS_0.6.38_PL.md`.
