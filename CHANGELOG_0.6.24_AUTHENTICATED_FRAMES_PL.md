# AI MASTER PRO 0.6.24 — uwierzytelnione ramki companion

## Zakres

- Dodano `CompanionFrameProtocol` jako czysty kontrakt ramki wiadomości; nie otwiera socketów i nie wykonuje operacji systemowych.
- Wprowadzono zamkniętą listę typów wiadomości: `PAIR_CONFIRM`, `BUNDLE_CHUNK`, `BUNDLE_COMPLETE`, `EXECUTION_RECEIPT`, `PING`, `CLOSE`.
- Dodano wyprowadzanie 256-bitowego klucza sesji przez HMAC-SHA256 z separacją domeny i powiązaniem z identyfikatorami sesji, klienta, companion oraz nonce. Wyprowadzenie wolno wykonać dopiero po zaakceptowaniu dowodu parowania.
- Każda ramka zawiera wersję protokołu, sesję, numer sekwencyjny, typ, Base64URL payload, rozmiar, SHA-256 i HMAC-SHA256.
- Odbiorca akceptuje wyłącznie dokładnie oczekiwany numer sekwencyjny; błędna autoryzacja nie zużywa numeru. Ponowne użycie ramki jest odrzucane.
- Limit payloadu jednej ramki: 1 MiB. Weryfikacja obejmuje Base64, rozmiar, digest i MAC.

## Bezpieczeństwo i ograniczenia

- HMAC daje uwierzytelnienie i integralność, nie szyfrowanie treści. W tej wersji nie ma poufności transmisji.
- Brak provisionowania PSK, TLS, socketów, discovery, desktop companion i OS sandboxa.
- Nie ma typu wiadomości do uruchamiania dowolnych poleceń systemowych.
- Nie wolno wysyłać sekretów przez przyszły transport, dopóki nie zostanie dodana i zweryfikowana warstwa szyfrująca.
- Kod importowanego projektu nie jest uruchamiany; `PROJECT_TESTS` pozostaje `NOT_RUN`.

## Testy

- 12/12 wybranych testów Kotlin/JVM PASS: 5 testów parowania z 0.6.23 i 7 testów ramek/wyprowadzania klucza z 0.6.24.
- Dodatkowo istniejący zestaw regresyjny 0.6.23: 17/17 PASS (parowanie, eksport bundle, manifest i receipt). Pięć testów parowania pokrywa się z powyższym zestawem; nie należy sumować wyników jako 29 unikalnych testów.
- Testy uruchomione w lokalnym harnessie Kotlin/JVM z lekkim stubem adnotacji JUnit; nie jest to pełny Gradle test suite.
