# 0.6.61-SOURCE — Companion TLS receipt lifecycle

**Typ:** automatyczne wygaszanie i cleanup receipt-ów TLS; bez APK.  
**Baza:** `0.6.60-SOURCE`.  
**Aplikacja:** `versionName=0.6.54`, `versionCode=80` — bez zmian.

## Zmiany
- `IssuedPeer` przechowuje socket oraz `issuedAtNanos` z monotonicznego `System.nanoTime()`.
- Receipt-y wygasają autonomicznie po 30 sekundach przez wspólny, pojedynczy daemon `ScheduledThreadPoolExecutor`; zadania są anulowane po consume/discard i usuwane z kolejki po anulowaniu.
- Dodatkowy purge usuwa wpisy dla zamkniętych socketów i nieważnych `SSLSession`; jest wywoływany przed przydzieleniem slotu nowemu receipt-owi oraz przed próbą jego konsumpcji.
- Po expiry wpis jest zdejmowany z mapy pod lockiem profilu, a socket zamykany poza lockiem. Dzięki temu porzucone połączenie nie czeka na następne wywołanie API, aby zwolnić zasób.
- Dodano wewnętrzny seam `purgeExpiredReceipts(nowNanos)` do deterministycznego testu.
- `discard(receipt)` usuwa dokładnie capability wydane przez dany profil i zamyka powiązany socket poza profile lockiem.
- Wszystkie ścieżki purge/discard/invalid-session zamykają socket poza blokadą profilu.
- TLS session ID jest zerowany po discard/expiry/invalid session oraz po przekazaniu kopii do ownera; owner czyści własną kopię przy `CLOSED`/`FAILED` i błędnej aktywacji.
- Dodano testy: receipt wygasa i zamyka socket; ręcznie zamknięty socket jest usuwany z ledgeru przed TTL.

## Walidacja wykonana
- `kotlinc -Werror` dla rzeczywistych klas core TLS + owner + gate/registry: PASS.
- `CompanionSessionOwnerTest`: 21/21 PASS przez lokalny JVM reflection harness z rzeczywistymi certyfikatami testowymi; NIE jest to JUnit/Gradle.
- Android Studio / Gradle / APK / Android Keystore / urządzenie: NOT RUN.

## Ograniczenia
- Registry, gate i binding store nadal pozostają w `drafts/pkt4`, poza Android source set.
- R6 ma TTL i jawny cleanup w profilu, ale pełna walidacja end-to-end i test limitu 32 receipt-ów wymagają integracji.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
