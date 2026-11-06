# AI MASTER PRO 0.6.4 — Komunikacja na żywo i Peer Review

## Punkt 1 — rzeczywisty dziennik komunikacji
- Zakładka „Rozmowy” pokazuje nowe zdarzenia natychmiast, bez ponownego otwierania ekranu.
- Każde wywołanie agenta zapisuje rzeczywiste żądanie tekstowe: identyfikator zadania, agenta, dostawcę, model, numer próby, prompt systemowy i prompt użytkownika.
- Pełne odpowiedzi agentów są rejestrowane jako zdarzenia REPORT; skróty 180 znaków pozostają wyłącznie w pomocniczym dzienniku podsumowań.
- Rejestrowana jest również pełna prośba MASTER-a o syntezę i odpowiedź modelu MASTER-a.
- Treść binarna załączników (Base64) nie jest zapisywana. Dziennik pokazuje tylko MIME i informację, czy załącznik był obecny.
- Zachowano historię per rozmowa, limit 150 ostatnich zdarzeń i czyszczenie dziennika.
- Nowe zdarzenia z równoległych wątków są dopisywane sekwencyjnie i przekazywane do UI na głównym wątku.
- Dodano test sieciowy sprawdzający logowanie rzeczywistego żądania i odpowiedzi.

## Punkt 2 — rzeczywista wzajemna recenzja agentów
- Po niezależnej analizie MASTER może uruchomić do dwóch dodatkowych zadań recenzenckich.
- Recenzent otrzymuje rzeczywisty raport innego agenta i ma wskazać błędy, braki dowodowe, ryzyka oraz konkretne poprawki.
- Recenzje są osobnymi wywołaniami API i widać je w dzienniku jako kolejne zlecenia oraz odpowiedzi.
- Dodatkowe wywołania przechodzą przez Smart Budget z osobną rezerwacją i pozostawieniem budżetu na syntezę MASTER-a.
- Gdy nie ma co najmniej dwóch poprawnych raportów/specjalistów lub Smart Budget nie pozwala na recenzję, etap jest pomijany bez blokowania podstawowej syntezy.
- Raporty recenzenckie trafiają do kontekstu syntezy i lokalnej kontroli konfliktów. MASTER ma traktować je jako krytyczną opinię, a nie automatycznie potwierdzony fakt.
- Wyniki Peer Review są rejestrowane w Agent Efficiency Tracker i Learning Journal.

## Bezpieczeństwo i ograniczenia
- Dziennik korzysta z istniejącego maskowania kluczy API i nagłówków Bearer.
- Żadne bajty załączników nie są zapisywane w dzienniku.
- Wersja podniesiona do 0.6.4 (versionCode 27).
- Kompilacja i testy nie zostały potwierdzone w tym środowisku: Gradle Wrapper 8.13 nie mógł pobrać dystrybucji z powodu niedostępności DNS `services.gradle.org`.
