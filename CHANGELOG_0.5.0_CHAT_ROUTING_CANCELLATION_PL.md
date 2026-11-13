# AL MASTER PRO 0.5.0 — jakość agentów, czat i realne anulowanie

Baza kodu: 0.3.9 (ciągłość danych i istniejące mechanizmy zachowane). Wersja aplikacji: `0.5.0`, `versionCode 22`.

## 1. Agenci uczą się na ocenach użytkownika — lokalnie, bez tokenów

- Dodano czysty, testowalny `AgentQualityRanker`.
- Oceny 👍/👎 raportów agentów są używane jako lokalny sygnał jakości.
- Próg wejścia do rankingu: 5 niezależnych identyfikatorów zadań.
- Ponowna ocena tego samego zadania nie zwiększa liczby próbek.
- Wynik jakości jest wygładzany (Laplace), aby mała próba nie dawała skrajnych wyników.
- Jakość może rozstrzygać wyłącznie remisy w lokalnym dopasowaniu słów kluczowych. Nigdy nie przebija agenta, którego dopasowanie do polecenia jest silniejsze.
- Nie uruchamia dodatkowego modelu, nie zmienia przypisań providerów/modeli i nie zwiększa liczby agentów.
- Udane przebiegi agentów zapisują kategorię kompetencji, aby oceny można było wiązać z dziedziną.

## 2. Czat — ocena każdej odpowiedzi, a nie tylko ostatniej

- Każda odpowiedź asystenta otrzymuje stabilny identyfikator żądania.
- Identyfikator jest przechowywany w rozmowie obok odpowiedzi i łączony z właściwym wpisem dziennika.
- Pod każdą odpowiedzią zakończoną sukcesem widać rolę, faktyczny provider/model i czas wykonania.
- Każdą odpowiedź można osobno ocenić 👍/👎; zmiana oceny aktualizuje ten sam rekord.
- Dla starszych rozmów bez identyfikatorów dodawane są puste znaczniki **na początku listy** (przed nowymi ID), aby nie przypisać oceny do niewłaściwej odpowiedzi.
- Mapowanie ID odpowiedzi ma osobny czysty helper i testy migracji.
- Zachowano oddzielny wątek „Asystent Kodu”; wysłanie wiadomości nie przenosi już użytkownika do zwykłego czatu MASTER-a.
- Pasek Asystenta ma ten sam selektor profilu/modelu co czat MASTER-a; przycisk nowej rozmowy tworzy nowy identyfikator wątku Asystenta, nie kasując poprzednich.
- Starsze wątki Asystenta zachowują swój typ i po ponownym otwarciu wracają do widoku Asystenta, a nie do czatu MASTER-a.
- Ekran Asystenta nie jest już opakowany w zewnętrzny ScrollView, który mógł przewijać composer poza ekran.
- Otwieranie rozmowy aktualizuje `last_user_chat_id`, dzięki czemu nawigacja nie wraca do poprzedniego wątku.

## 3. Stop naprawdę zatrzymuje pracę

- `ProviderClient` rejestruje aktywne połączenia HTTP i rozłącza je po naciśnięciu Stop.
- Każde zadanie nadrzędne otrzymuje niezmienny `RequestScope`; wszystkie równoległe wywołania agentów muszą używać tego samego tokenu zakresu.
- Nowy zakres i Stop unieważniają starsze tokeny oraz rozłączają stare połączenia, więc spóźniony worker nie może wznowić POST-a po rozpoczęciu kolejnego zadania.
- Zakres czatu nie anuluje niezależnych, niepowiązanych z nim żądań katalogu modeli.
- Orkiestrator jest przerywany, a executor agentów otrzymuje przerwanie.
- Anulowane żądanie ma kategorię `CANCELLED` i nie jest traktowane jako awaria merytoryczna.
- Po anulowaniu blokowane jest uruchamianie spóźnionych wywołań z tego samego batcha; dopiero nowy, jawnie rozpoczęty zakres pracy odblokowuje następne zapytanie.
- Anulowanie nie uruchamia fallbacku i nie ponawia POST-a.
- Widok Stop zachowuje bieżący ekran, wybrany profil i rozmowę Asystenta.

## 4. Korelacja i izolacja rozmów

- Tokeny czatu są zapisywane pod unikalnym `requestTaskId`, nie pod samym ID rozmowy.
- Metadane dziennika odzwierciedlają faktycznie użyty provider/model po failoverze.
- Tryb pracy i rola są przechwytywane na początku żądania; zmiana aktywnej rozmowy nie zmienia kontekstu rozpoczętej pracy.
- Logi agentów są zapisywane do rozmowy, która je uruchomiła, również wtedy, gdy użytkownik otworzy inny wątek.
- Zapis i odczyt dziennika komunikacji agentów są synchronizowane; widoki renderują migawki listy, a nie współdzieloną listę modyfikowaną przez równoległe callbacki.

## 5. Testy i kontrola jakości

Dodano testy:
- progu 5 niezależnych zadań i deduplikacji ocen;
- wygładzania wyniku jakości;
- pierwszeństwa dopasowania słów kluczowych przed oceną historyczną;
- rozstrzygania remisów przez jakość;
- anulowania aktywnego POST-a, braku retry i blokowania spóźnionych wywołań.

Wykonane lokalnie w środowisku przygotowania:
- kompilacja izolowana `ProviderClient` ze stubami JSON: PASS;
- lokalny test HTTP: przerwanie aktywnego POST-a, klasyfikacja `CANCELLED`, brak ponowienia: PASS;
- kompilacja i test rankingu agentów: PASS;
- kompilacja i test czystego rankera jakości: PASS;
- kontrola parsera `MainActivity.kt`: brak wykrytych błędów składniowych.

Pełny build Android i testy Gradle nie zostały uruchomione: wrapper nie mógł pobrać Gradle 8.13 z `services.gradle.org` (`UnknownHostException`), a w środowisku nie ma Android SDK. APK nie jest dołączony.

## Zasady kosztów i bezpieczeństwa

- Statystyki, oceny i dobór agenta pozostają lokalne — 0 dodatkowych tokenów.
- MASTER nadal nie wykonuje automatycznej analizy jakości bez zgody użytkownika.
- Ranking nie zmienia konfiguracji API, kluczy ani przypisań modeli.
- Nie wprowadzono automatycznego cache odpowiedzi, który mógłby zwrócić nieaktualny wynik dla zmienionego zadania.
