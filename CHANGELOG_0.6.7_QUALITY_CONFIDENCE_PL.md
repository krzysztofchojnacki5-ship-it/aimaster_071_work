# AI MASTER PRO 0.6.7 — wiarygodność ocen jakości

- Rozszerzono lokalny ranker o Snapshot: wynik wygładzony, liczebność niezależnych zadań, próbę kategorii i poziom pewności.
- Dodano 95% przedział Wilsona, aby ranking pokazywał niepewność zamiast samego procentu.
- Zachowano deduplikację po taskId, więc ponowna ocena tej samej rozmowy nie pompuje próby.
- Wynik nadal nie zmienia automatycznie konfiguracji modeli; routing wykorzystuje go jedynie jako ostrożny sygnał jakości.
- Rozdzielono metryki jakości treści od niezawodności technicznej i czasu wykonania.
- Dodano testy przedziału ufności i liczebności próby.
