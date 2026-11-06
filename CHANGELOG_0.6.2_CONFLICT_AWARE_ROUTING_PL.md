# AI MASTER PRO 0.6.2 — kontrola rozbieżności i routing oparty na danych

## 1. Lokalna kontrola rozbieżności raportów
- Dodano `ReportConflictDetector`: deterministycznie wykrywa bezpośrednie rozbieżności liczbowe w porównywalnych liniach raportów różnych agentów.
- Wynik jest ostrzeżeniem, nie automatycznym rozstrzygnięciem. MASTER otrzymuje instrukcję, by nie wybierać arbitralnie wartości i oznaczyć konflikt jako nierozstrzygnięty, jeśli raporty nie dostarczają dowodu.
- Detektor nie wykonuje żadnego zapytania API. Brak wykrytego konfliktu nie jest przedstawiany jako dowód prawdziwości raportów.
- Dodano testy dla rozbieżnych i zgodnych wartości oraz instrukcji bez fałszywej deklaracji pełnej weryfikacji.

## 2. Ostrożny sygnał niezawodności agenta
- `AgentEfficiencyTracker.reliabilityScore` liczy wygładzony wskaźnik ukończenia zadań, dopiero od 8 niezależnych identyfikatorów zadań.
- Duplikaty taskId nie zwiększają próby.
- Wskaźnik opisuje wyłącznie niezawodność wykonania, nie jakość odpowiedzi.
- Routing wykorzystuje go wyłącznie jako tie-breaker: gdy istnieje ocena użytkownika i metryka operacyjna, waga wynosi odpowiednio 85% / 15%. Lepsze dopasowanie słów kluczowych nadal ma pierwszeństwo.

## Kompatybilność
- Bez zmian w formacie konfiguracji, kluczach API, historii czatów i backupach.
- Bez nowych zależności, uprawnień sieciowych i migracji danych.
- Wersja: 0.6.2, versionCode 26.
