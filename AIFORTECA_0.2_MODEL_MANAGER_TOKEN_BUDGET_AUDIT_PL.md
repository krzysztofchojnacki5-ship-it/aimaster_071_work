# AiFORTeca 0.2 — audyt menedżera modeli i budżetów tokenów

Data audytu: 2026-11-11  
Gałąź: `work/aiforteca-0.2-master-agent-foundation`  
Zakres: katalog modeli, księgowanie tokenów, limity, przygotowanie nowego menedżera modeli/agentów.

## 1. Stan potwierdzony w kodzie

### Rejestr użycia już istnieje
Plik: `app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt`

- `TokenRecord` zapisuje dostawcę, nazwę modelu, agenta, zadanie, tokeny wejściowe i wyjściowe, reasoning/cache oraz status pomiaru.
- Rejestr jest zapisywany lokalnie i obsługuje idempotencję oraz późniejsze uzupełnienie rzeczywistego usage.
- Monitor API ma już grupowanie według dostawcy, agenta i nazwy modelu.
- Aktualne limity obejmują globalny limit dzienny, limit dzienny agenta oraz opcjonalny limit kosztu za 30 dni.
- Ostrzeżenie istniejącego mechanizmu pojawia się przy 80% globalnego dziennego limitu tokenów lub limitu kosztowego. Nie jest to jeszcze limit per model.

### Ceny są obecnie zbyt mocno związane z księgowaniem
- `recordQuery()` wywołuje `getPricing()` i wymaga wyceny oraz kursu waluty przed trwałym zapisaniem usage.
- `planBatch()` wywołuje `getPricing()` przed przyjęciem zadania i rezerwuje także koszt.
- Jeżeli katalog zawiera dokładny wpis modelu z brakującą stawką wejścia lub wyjścia, `getPricing()` zgłasza błąd.
- Jeżeli dokładnego wpisu nie ma, kod może użyć ogólnego cennika zastępczego. Takie dopasowanie nie jest wystarczająco wiarygodne dla dowolnych endpointów OpenAI-compatible.

Wniosek: samo usunięcie walidacji byłoby niebezpieczne. Mogłoby zamienić brak ceny w pozornie zerowy koszt albo zastosować nieprawidłową stawkę.

## 2. Docelowa zasada rozliczania

Każdy profil modelu ma niezależny stan ceny:

1. `KNOWN_PAID` — kompletna, zweryfikowana stawka; koszt jest obliczany.
2. `CONFIRMED_FREE` — jawnie potwierdzony model bez opłat API lub model lokalny; koszt wynosi zero z odpowiednim źródłem potwierdzenia.
3. `UNKNOWN_APPROVED` — cena nieznana, ale użytkownik świadomie zezwolił na użycie; tokeny są księgowane, koszt pozostaje oznaczony jako nieznany, nie jako 0 PLN.
4. `UNKNOWN_BLOCKED` — cena nieznana i brak zgody; model nie jest używany.

Nie wolno uznawać braku stawek za darmowy model. Nie wolno też blokować księgowania rzeczywistego usage tylko dlatego, że nie udało się ustalić ceny.

### Bezpieczeństwo budżetu
- Limit tokenów działa niezależnie od ceny.
- Nieznany koszt nie może być dodawany do sumy kosztów jako wiarygodne 0 PLN.
- Gdy aktywny jest limit finansowy, zadanie z nieznaną ceną wymaga osobnej, świadomej zgody i wyraźnego komunikatu, że limit finansowy nie może go w pełni kontrolować. Alternatywnie użytkownik może pozostawić taki model zablokowany.
- Dla modeli płatnych z ceną znaną nadal obowiązuje rezerwacja i limit finansowy.
- Historia ma rozróżniać koszt znany, potwierdzone zero i koszt nieznany.

## 3. Docelowy limit per model

Tożsamość limitu powinna być stabilna i rozdzielać dostawców:
- `providerId`
- znormalizowany `baseUrl`
- dokładne `modelName`

Każdy profil modelu powinien mieć:
- własny limit tokenów w wybranym okresie (domyślnie dziennym),
- osobny licznik wejścia, wyjścia i sumy,
- próg ostrzegawczy domyślnie 90% (konfigurowalny),
- akcję po osiągnięciu 100%: blokuj albo przełącz wyłącznie na zatwierdzony model zapasowy,
- historię dzienną i 30-dniową.

Ostrzeżenie 90% powinno być wyzwalane przy przekroczeniu progu, nie przy każdym kolejnym żądaniu. Po rozpoczęciu nowego okresu stan ostrzeżenia resetuje się dla nowego okresu.

Licznik aplikacji należy odróżnić od limitów/quota dostawcy. Limity dostawcy pokazujemy tylko wtedy, gdy API dostawcy zwróci wiarygodne dane.

## 4. Menedżer modeli i agentów — wymagania UX

Menedżer ma działać jak panel operacyjny IDE:
- jedna lista aktywnych i nieaktywnych profili modeli;
- karta modelu: dostawca, endpoint, dokładny identyfikator modelu, stan połączenia, stan ceny, przypisane role/agentów;
- tokeny wejściowe/wyjściowe/razem i wykorzystanie limitu;
- ostrzeżenie 90%, stan limitu 100%;
- test endpointu i autoryzacji oddzielony od sprawdzenia dostępności modelu;
- wyłączenie/usunięcie profilu usuwa go z kandydatów routingu, ale nie kasuje historii tokenów;
- błędny test połączenia nie usuwa automatycznie profilu;
- fallback tylko do profilu jawnie zatwierdzonego;
- brak profili nie może powodować cichego podstawienia starego modelu.

## 5. Kolejność implementacji

1. **Core usage/pricing:** oddzielić zapis tokenów od wyceny; dodać jawny stan ceny i zachować kompatybilność ze starym rejestrem.
2. **Testy regresji:** brak ceny, potwierdzone zero, nieznana cena zatwierdzona/niezatwierdzona, opóźnione REAL usage, odtworzenie po restarcie, błędny/uszkodzony katalog.
3. **Per-model budget store:** trwałe limity, agregacja per dokładna tożsamość profilu, progi 90/100%, rezerwacje równoległych zadań.
4. **UI menedżera modeli:** karty profili i liczniki, konfiguracja limitu, zgoda na nieznany koszt.
5. **Integracja routingu:** wyłącznie aktywne i zatwierdzone profile; kontrolowany fallback.
6. **CI i APK:** build + pełne testy; dopiero po potwierdzeniu wyników oznaczyć etap jako zakończony.

## 6. Status po tym audycie

- [x] Odczytano aktualny kod rejestru tokenów i ekranu katalogu modeli.
- [x] Potwierdzono istniejące grupowanie zużycia według nazwy modelu w Monitorze API.
- [x] Zidentyfikowano twardą zależność księgowania i planowania zadań od kompletnej wyceny.
- [x] Ustalono bezpieczny model danych i kolejność zmian.
- [x] Dodano do TokenRecord pole costKnown; starsze rekordy bez tego pola są odczytywane jako historyczne rekordy z wyceną.
- [x] Zapis tokenów może być jawnie zatwierdzony dla niekompletnej stawki katalogowej; rekord zachowuje tokeny i oznacza koszt jako nieznany, nie jako 0 PLN.
- [x] Podsumowania kosztów pomijają rekordy z nieznaną ceną, zachowując ich tokeny w agregatach.
- [x] Nieznana cena nie może być zatwierdzona, gdy aktywny jest miesięczny limit finansowy.
- [x] Planowanie zadań blokuje się przy aktywnym limicie finansowym, jeśli w ostatnich 30 dniach występuje rekord o nieznanym koszcie.
- [x] Dodano testy: domyślna blokada, jawna zgoda przy wyłączonym limicie finansowym, trwałość flagi po restarcie oraz zgodność ze starymi rekordami.
- [ ] CI dla najnowszego commitu jest w toku; wyniki testów/builda nie są jeszcze potwierdzone.
- [ ] Nie przygotowano nowego APK.

Następny krok: implementacja punktu 1 w `ApiTokenTracker` z testami, bez zmiany numeru wersji 0.2 i bez naruszania istniejących danych użytkownika.
