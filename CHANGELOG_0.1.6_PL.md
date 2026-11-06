# AI Master Pro 0.1.6 — czat

## Zmiany
- Przebudowano ekran czatu: transcript wiadomości ma własne przewijanie, a pole pisania i przyciski pozostają przypięte u dołu ekranu.
- Pole wiadomości obsługuje wielowierszowe pisanie (do 7 widocznych linii) i lepiej nadaje się do dłuższych promptów.
- Dodano czytelniejsze dymki wiadomości, nazwę asystenta oraz szybkie kopiowanie odpowiedzi.
- Dodano menu rozmów: nowy czat, ostatnie rozmowy, przypięte rozmowy i bibliotekę załączników.
- Dodano przypinanie/odpinanie rozmowy z zapisem lokalnym.
- Rozszerzono wybór załączników o osobny wybór obrazów oraz dokumentów/kodu/tekstu/PDF.
- Dodano widoczny stan „AI myśli” z animowanym wskaźnikiem i tekstem etapu pracy; status aktualizuje się podczas pracy agentów.
- Zachowano dotychczasowe mechanizmy dostawców, agentów, historii oraz lokalnego zapisu rozmów.

## Ograniczenia jawnie pozostawione
- To natywna implementacja Androida inspirowana układem czatu ChatGPT, nie kopia 1:1 jego interfejsu.
- Biblioteka pokazuje odnalezione w historii nazwy załączników; nie kopiuje plików do osobnego magazynu aplikacji.
- Obsługa obrazów/PDF zależy od dostawcy i modelu. Groq/niektóre endpointy OpenAI-compatible mogą nie przyjmować obrazów lub PDF.
- Odpowiedzi nadal pojawiają się po zakończeniu żądania API; nie dodano strumieniowania token po tokenie.
- Kompilacja APK nie została potwierdzona w tym środowisku, ponieważ Gradle nie mógł pobrać dystrybucji z internetu.
