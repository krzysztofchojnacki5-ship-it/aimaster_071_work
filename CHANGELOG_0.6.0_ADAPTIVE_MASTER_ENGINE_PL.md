# AI MASTER PRO 0.6.0 — adaptacyjny silnik MASTER-a

## 1. Inteligentne rozpoznawanie powtórzeń
- Dodano lokalny cache raportów specjalistów (SharedPreferences), ograniczony do 60 wpisów i 72 godzin.
- Klucz SHA-256 obejmuje agenta, cel, pełny prompt, system prompt agenta, instrukcję trybu, kontekst projektu, typ i zawartość załącznika oraz model.
- Cache przyjmuje wyłącznie niepuste, ukończone raporty. Błędy, odpowiedzi MASTER-a i nieudane zadania nie są zapisywane.
- Trafienie cache omija wywołanie API danego specjalisty; brak zgodności lub przeterminowanie wymusza świeże wykonanie.

## 2. Samokontrola MASTER-a
- Dodano lokalną kontrolę pustej/zbyt krótkiej odpowiedzi, instrukcji bez sekwencji kroków, porównania bez elementów porównawczych oraz placeholderów.
- Kontrola nie uruchamia dodatkowego modelu. Ostrzeżenia są logowane i dołączane do odpowiedzi, aby nie ukrywać wykrytej luki.
- To kontrola strukturalna, nie dowód prawdziwości faktów ani wykonania testów.

## 3. Adaptacyjny budżet
- Trudność jest oceniana lokalnie na podstawie sygnałów zadania i liczby domen; bez dodatkowego API.
- Dobierana jest liczba specjalistów (1–4), limit raportu na agenta oraz rezerwa syntezy MASTER-a.
- Jawne żądanie zespołu zachowuje możliwość użycia do 4 agentów; routing zespołowy bez jawnego żądania utrzymuje minimum 2.
- Smart Budget nadal blokuje zadania, których nie można bezpiecznie sfinansować; rezerwacja jest liczona tylko dla zadań bez trafienia cache.

## Bezpieczeństwo i kompatybilność
- Bez nowych zależności, uprawnień sieciowych, zmian kluczy API, profili providerów ani schematu backupu.
- Cache jest lokalny dla urządzenia. Zmiana promptu, kontekstu, załącznika lub modelu unieważnia dopasowanie.
