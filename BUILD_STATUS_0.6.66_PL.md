# BUILD STATUS — 0.6.66-SOURCE

**Typ:** poprawki współbieżności i dispatchu Companion; paczka źródłowa, bez APK.  
**Baza:** 0.6.65-SOURCE.  
**Wersja aplikacji:** `0.6.54` / `versionCode=80` (bez zmian).

## Wykryty i naprawiony problem
Rejestr mógł odłączyć uchwyt pod blokadą, a zamknąć go po zwolnieniu blokady. W tym oknie inny wątek mógł ponownie zarejestrować ten sam obiekt z nowym ticketem; spóźnione zamknięcie starej sesji zamknęłoby wtedy także nową. Dodano identity-based zbiór `closing`, który rezerwuje uchwyt do końca próby zamknięcia. Rejestracja tego samego obiektu w tym czasie jest odrzucana.

## Dodatkowe utwardzenie
- Każdy uchwyt z odwołanej grupy otrzymuje próbę zamknięcia, nawet jeśli inny rzuci `Error`; pierwszy fatalny błąd jest propagowany dopiero po zakończeniu wszystkich prób.
- Publisher analogicznie nie przerywa dispatchu listenerów po fatalnym `Error`; propaguje go po wywołaniu pozostałych listenerów.
- Zwykłe wyjątki nadal są agregowane zgodnie z dotychczasowym kontraktem.

## Wyniki
- `kotlinc -Werror`: PASS.
- Registry: 25/25 PASS w lokalnym harnessie.
- Publisher: 3/3 PASS w lokalnym harnessie.
- Binding lifecycle: 2/2 PASS w lokalnym harnessie.
- Owner/TLS/loopback gate: 24/24 PASS, w tym nowy test fatalnego validatora i bounded socket cleanup.
- Łącznie: **54/54 PASS**. Shim JUnit służył wyłącznie do uruchomienia metod testowych; to nie jest JUnit, Gradle ani test Android.
- ZIP integrity i SHA-256 sprawdzone przy tworzeniu paczki.

## NOT RUN
- `./gradlew testDebugUnitTest`
- `./gradlew assembleDebug`
- Android Keystore / Robolectric / testy na urządzeniu

## Status architektury
- Registry/gate/binding nadal pozostają w `drafts/pkt4`, poza aktywnym buildem.
- Brak produkcyjnego spięcia store → publisher → binding → registry → socket.
- Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
- Nie twierdzę, że istnieje APK 0.6.66.

## Następny etap
Przeprowadzić testy kontraktu granicznego store → publisher → binding → registry, szczególnie commit trust removal kontra nowe admission, global wipe, równoległe revoke oraz zamykanie bindingu w trakcie dispatchu. Następnie ocenić bezpieczną integrację z `CompanionSessionOwner` bez wykonywania I/O pod monitorami store/owner.
