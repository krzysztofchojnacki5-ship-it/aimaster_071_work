# AL MASTER PRO 0.3.6 — Pamięć i uczenie operacyjne

## Co zmieniono
- Dodano `LearningJournalStore`: lokalny, trwały dziennik do 500 zdarzeń pracy agentów i czatu.
- Dziennik zapisuje wyłącznie metadane operacyjne: czas, rolę/agenta, provider/model, status, czas wykonania, liczbę prób i kategorię błędu. Nie zapisuje promptów, odpowiedzi ani kluczy API.
- Zdarzenia agentów są zapisywane po zakończeniu batcha; zdarzenie czatu po zakończeniu odpowiedzi.
- `AiMemoryStore.contextString()` dołącza zagregowane statystyki historyczne agentów. Statystyki są wyraźnie oznaczone jako operacyjne, a nie jako ocena poprawności merytorycznej.
- Wspólny kontekst pamięci i statystyk jest przekazywany również specjalistom, a nie tylko MASTER-owi podczas syntezy.
- Widok Pamięć pokazuje stan dziennika, zapisane zasady, historię, dodawanie zatwierdzonych zasad i czyszczenie dziennika bez kasowania zasad pamięci.
- Dodano testy Robolectric dla zapisu, agregacji i niezależnego czyszczenia dziennika.

## Granice funkcji
- To nie jest fine-tuning ani trening wag modelu.
- Aplikacja nie tworzy automatycznie faktów/zasad na podstawie niezweryfikowanych odpowiedzi. Nowe reguły użytkownik zatwierdza ręcznie.
- Skuteczność przebiegu oznacza status techniczny zadania, nie prawdziwość lub jakość odpowiedzi.
- Dane są przechowywane lokalnie w SharedPreferences. Usunięcie danych aplikacji usuwa również dziennik i pamięć lokalną.
