# AI MASTER PRO 0.6.5 — Utwardzenie komunikacji i Peer Review

## Zakres
- Wydzielono deterministyczny `PeerReviewPlanner`, który buduje zadania recenzenckie poza `MainActivity`.
- Recenzenci nie recenzują własnych raportów.
- Przy dostępności odpowiedniej liczby specjalistów recenzje są rozdzielane między różnych recenzentów, zamiast niepotrzebnie obciążać jednego agenta.
- `reviewOfAgentId` jest przenoszone od zadania przez silnik wykonawczy do wyniku; cel recenzji jest widoczny w śladzie REQUEST i w kontekście syntezy.
- Kontrola konfliktów liczbowych porównuje wyłącznie niezależne raporty specjalistów. Recenzje są krytyczną oceną raportów, a nie dodatkowym niezależnym źródłem faktów, więc nie są mieszane z tym porównaniem.
- Wydzielono wspólny sanitizer dziennika i rozszerzono maskowanie na typowe klucze OpenAI/OpenRouter, Groq, Google, GitHub, Slack, Bearer oraz pola sekretów w formacie key=value/JSON.
- Widok „Rozmowy” pokazuje licznik zdarzeń, żądań, odpowiedzi, recenzji i błędów; długie żądania/raporty są domyślnie zwinięte, ale ich pełna treść pozostaje dostępna po rozwinięciu.
- Dodano testy planowania recenzji, atrybucji recenzji, maskowania sekretów i śladu HTTP dla Peer Review.

## Zachowanie kosztowe
- Limit maksymalnie dwóch recenzji pozostaje bez zmian.
- Każda recenzja nadal przechodzi przez Smart Budget i osobną rezerwację.
- Brak budżetu lub błąd recenzji nie blokuje syntezy raportów pierwotnych.

## Weryfikacja
- Integralność wejściowego ZIP 0.6.4: poprawna.
- Test kompilacji Kotlin dla `CommunicationLogSanitizer.kt`: zakończony powodzeniem.
- Pełne testy Gradle i APK: nieuruchomione w tym środowisku (brak zainstalowanego Gradle i brak pobranej dystrybucji wrappera 8.13).
