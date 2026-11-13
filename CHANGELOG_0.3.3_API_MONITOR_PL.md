# AI MASTER PRO 0.3.3 — Monitor API

## Naprawy i usprawnienia

- Przebudowano ekran Monitor API: domyślnie pokazuje bieżący dzień zamiast pustej sesji po ponownym uruchomieniu procesu.
- Dodano czytelne kafelki: liczba zapytań, tokeny wejściowe/wyjściowe, koszt szacowany i liczba pomiarów REAL.
- Dodano zestawienie zużycia według dostawcy, agenta i modelu.
- Dodano rozróżnienie REAL / ESTIMATED / UNAVAILABLE oraz opis znaczenia tych statusów. Status REAL jest przyznawany tylko wtedy, gdy API zwróciło oba liczniki: wejściowy i wyjściowy.
- Dodano szczegóły pojedynczego zapytania, kopiowanie raportu i historii okresu, odświeżanie oraz potwierdzane czyszczenie historii.
- Testy modeli, dostawców i przypisań agentów zapisują teraz usage po udanym teście, dzięki czemu test nie znika z Monitora API.
- Naprawiono błąd duplikowania pierwszego rekordu przy pierwszym zapisie do pustej historii.
- Poprawiono filtr „Sesja”: pokazuje rekordy od początku bieżącego procesu, pobrane z trwałej historii, a nie wyłącznie chwilową listę w pamięci.
- Ustawienie miesięcznego limitu kosztów na 0 wyłącza limit pieniężny; limit tokenów dziennych nadal obowiązuje.
- Dodano testy jednostkowe dla zapisu, filtrowania, czyszczenia danych i działania wyłączonego limitu kosztów.

## Ograniczenia

- Monitor pokazuje zużycie zarejestrowane przez aplikację. Nie odczytuje automatycznie całego zużycia konta u dostawcy ani zapytań wykonanych poza aplikacją.
- REAL oznacza, że licznik usage został zwrócony w odpowiedzi API. ESTIMATED jest szacunkiem opartym o długość tekstu.
- Koszt jest szacowany na podstawie lokalnego cennika aplikacji; nie jest fakturą ani potwierdzeniem rozliczenia dostawcy.
- Kompilacja Android i testy Gradle wymagają weryfikacji w Android Studio; nie należy uznawać ich za zaliczone bez uruchomienia.
