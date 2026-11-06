# AI MASTER PRO 0.6.25 — ograniczony kodek ramek companion

## Cel

Domknięcie brakującej warstwy serializacji dla ramek 0.6.24 bez uruchamiania transportu ani wprowadzania własnego szyfrowania.

## Zmiany

- Dodano `CompanionFrameWireCodec` z wersjonowanym binarnym formatem `AIMF`.
- Parser sprawdza maksymalny rozmiar całej ramki przed rozpoczęciem parsowania.
- Każde pole tekstowe ma limit długości sprawdzany przed alokacją bufora.
- Dekodowanie UTF-8 jest ścisłe: błędne i niepoprawne sekwencje nie są zastępowane znakami zastępczymi.
- Akceptowane są wyłącznie znane typy komunikatów i kierunki; nie ma pola polecenia ani dowolnego typu.
- Wymagany jest kanoniczny, bezpaddingowy Base64URL o długości zgodnej z deklarowanym rozmiarem payloadu.
- Odrzucane są nieznane wersje, nieprawidłowe metadane, skrócone ramki i nadmiarowe bajty na końcu.
- Kodek nie uznaje ramki za uwierzytelnioną. Po dekodowaniu obowiązkowo należy wywołać `CompanionFrameProtocol.Receiver.verify()`.

## Granice bezpieczeństwa

- Nie dodano socketów, discovery, TLS, szyfrowania payloadu ani provisionowania PSK.
- Nie przesyłać poufnych danych przez obecny kontrakt ramek; HMAC zapewnia integralność i uwierzytelnienie, nie poufność.
- Nie ma desktop companion ani OS-level sandboxa. Nie uruchamiamy kodu importowanego projektu; `PROJECT_TESTS` pozostaje `NOT_RUN`.
