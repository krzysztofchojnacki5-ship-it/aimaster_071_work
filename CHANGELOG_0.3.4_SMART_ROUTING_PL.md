# AL MASTER PRO 0.3.4 — Smart Routing, Rezerwacje Budżetu i Efektywność

## Nowe funkcje

### 1. Historia kondycji modeli
- Lokalny rejestr sukcesów, błędów, kategorii awarii i czasu odpowiedzi.
- Bez zapisywania promptów, odpowiedzi ani kluczy API.
- Ostatnie zdarzenia z 7 dni wpływają na ranking profili zapasowych.
- Skonfigurowany model podstawowy pozostaje pierwszy, jeśli historia nie wskazuje istotnej niestabilności.
- HTTP 429, DNS/connect i timeouty otrzymują różne kary w rankingu.

### 2. Rezerwacje budżetu w czasie rzeczywistym
- `planBatch()` rezerwuje budżet atomowo w pamięci procesu.
- Kolejny batch uwzględnia rezerwacje wcześniejszych batchy, a nie tylko zapisane zużycie.
- Zarejestrowane zużycie zadania zmniejsza jego pozostałą rezerwację, aby nie liczyć tokenów podwójnie.
- Rezerwacje są zwalniane po zakończeniu batcha, także przy wyjątku.
- Zwykły czat sprawdza również aktywne rezerwacje batchy.
- Monitor API pokazuje liczbę aktywnych zadań i zarezerwowane tokeny.

### 3. Metryki efektywności agentów
- Zapisywana jest liczba ukończonych i nieudanych zadań, czas wykonania, liczba prób oraz faktycznie użyty profil.
- Monitor API prezentuje statystyki per agent i zestawia je z zapisanym zużyciem tokenów.
- Raport mierzy efektywność operacyjną, nie udaje automatycznej oceny merytorycznej jakości odpowiedzi.

## Integracja
- Historia kondycji jest używana w routingu zapasowych modeli w czacie i TeamRunEngine.
- Wyniki i metryki są lokalne; nie są wysyłane do zewnętrznych usług.
- Czyszczenie historii Monitor API czyści również historię kondycji modeli i metryki agentów.

## Ograniczenia i weryfikacja
- Rezerwacje są współdzielone w obrębie procesu aplikacji; nie są synchronizowane pomiędzy osobnymi procesami Android.
- Historia kondycji nie odczytuje prywatnych limitów RPM/TPM dostawcy. Wnioskuje jedynie z obserwowanych odpowiedzi i błędów.
- Statystyki efektywności nie oceniają semantycznej jakości raportów.
- Testy jednostkowe dodano dla rezerwacji równoległych, rankingu modeli i agregacji efektywności.
- Kompilacja Android i testy Gradle wymagają uruchomienia w Android Studio; nie deklarujemy ich jako zaliczonych bez wykonania.
