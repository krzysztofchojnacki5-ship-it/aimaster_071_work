# AL MASTER PRO 0.5.1 — poprawki czatu i wiarygodności uczenia

Wersja aplikacji: `0.5.1`, `versionCode 23`. Baza: 0.5.0. Zachowano istniejące magazyny danych, konfiguracje providerów, klucze API i historię rozmów.

## 1. Naprawa nawigacji dedykowanego Asystenta Kodu

- Ocena odpowiedzi w widoku Asystenta odświeża ten sam widok, a nie zwykły czat MASTER-a.
- Usunięcie załącznika z kompozytora zachowuje aktualny typ rozmowy.
- Dodano czystą politykę `ChatScreenPolicy` i testy obu ścieżek.

## 2. Ranking agentów nie miesza już różnych rodzajów ocen

- Ranking specjalistów i metryka liczby ocenionych agentów uwzględniają wyłącznie `targetType=AGENT`.
- Oceny `CHAT_ANSWER` i `MASTER` nie zanieczyszczają rankingu raportów specjalistów.
- Ranking modeli/providera pokazuje rodzaj ocenianego elementu w kluczu grupy.
- Kontekst ręcznej analizy MASTER-a ma oddzielne oceny agentów i modele z podziałem na typ oceny.

## 3. Ostrożniejsze uczenie kompetencji

- Dla kategorii z co najmniej 5 niezależnymi zadaniami używany jest wynik kategorii.
- Dla kategorii z mniejszą próbą lokalne oceny są wygładzane w kierunku globalnego wyniku agenta (prior o wadze 4).
- Nadal wymagane jest minimum 5 niezależnych zadań ogółem.
- Wynik jakości pozostaje wyłącznie rozstrzygnięciem remisu w lokalnym dopasowaniu słów kluczowych; nie uruchamia API i nie zmienia przypisań modeli.

## 4. Czytelność czatu

- Markdownowe linki HTTP/HTTPS są renderowane jako klikalne odnośniki w kolorze akcentu.
- Surowe URL-e nadal są obsługiwane przez Android Linkify.
- Nie dodano zewnętrznych bibliotek ani wywołań API.

## 5. Testy dodane

- Nawigacja z widoku Asystenta i pozostałych ekranów.
- Wygładzanie rzadkich ocen kategorii względem globalnego wyniku.
- Rozdzielenie ocen raportów agentów od ocen końcowej odpowiedzi.

## Koszt i kompatybilność

- Wszystkie nowe obliczenia są lokalne: 0 tokenów.
- Nie dodano żadnych zależności.
- Nie zmieniono formatu kopii zapasowych ani nazw SharedPreferences.
- Nie zmieniono kluczy API, modeli ani przypisań providerów.
