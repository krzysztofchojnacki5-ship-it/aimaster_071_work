# AL MASTER PRO 0.3.9 — kontekst zadaniowy i oszczędna synteza

## Najważniejsze zmiany
- Pamięć wspólna jest dobierana lokalnie do bieżącego promptu (dopasowanie słów kluczowych); wstrzykiwany kontekst ma limit 2 400 znaków i 8 reguł.
- Dwa podstawowe zalecenia jakościowe są utrzymywane jako reguły bazowe, a pozostałe wpisy są sortowane według trafności i świeżości.
- Operacyjne statystyki agentów nie są automatycznie dołączane do zwykłych zapytań. Pozostają w lokalnych magazynach i w analizie jakości na żądanie.
- Pamięć przekazywana równoległym agentom ma limit 1 800 znaków, aby ograniczyć powielanie identycznego kontekstu w wielu wywołaniach.
- Synteza MASTER-a wykorzystuje wyłącznie niepuste raporty ze statusem COMPLETED. Nieudane/blocked przebiegi są wykluczone z treści merytorycznej.
- Raporty są ograniczane do 6 000 znaków każdy i 18 000 znaków łącznie; skrócenie jest sygnalizowane w logu.
- Rezerwa tokenowa batcha uwzględnia przybliżony rozmiar kontekstu raportów, pamięci i promptu.

## Testy dodane
- `AiMemoryStoreTest`: limit pamięci, ranking trafności i brak automatycznego wstrzykiwania metryk operacyjnych.
- `SynthesisContextBuilderTest`: wykluczanie błędów/blocked, limit łączny, skracanie raportów i puste wyniki.

## Zasady zachowania
- Nie dodano automatycznych wywołań AI.
- Wybór pamięci i budowa kontekstu są lokalne i nie zużywają tokenów.
- Nie zmieniono kluczy API, przypisań modeli ani istniejących historii.
- Dane pamięci i feedbacku zachowują dotychczasowy format przechowywania.

## Weryfikacja
Pełny build Android/Gradle nie został potwierdzony w tym środowisku. Należy uruchomić synchronizację i `Build > Make Project` w Android Studio z JDK 17.
