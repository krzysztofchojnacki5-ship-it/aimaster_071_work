# AI MASTER PRO — poprawki 03.11.2026

## Katalog modeli
- Usunięto wbudowaną, nieaktualną listę modeli chmurowych deklarowanych wcześniej jako darmowe.
- Zmieniono klasyfikację na fail-closed: brak wiarygodnego potwierdzenia oznacza `UNKNOWN`, a nie `FREE`.
- Groq, Gemini, Mistral i pozostałe modele chmurowe nie są automatycznie uznawane za darmowe tylko dlatego, że API zwróciło je w `/models`.
- OpenRouter dopuszcza wyłącznie jawne identyfikatory `:free` oraz `openrouter/free`; limity konta nadal mogą obowiązywać.
- Ollama jest traktowana jako lokalna, bezpłatna usługa (koszty sprzętu/energii nie są wyceniane przez aplikację).
- Katalog odświeża się automatycznie po otwarciu selektora. Ostatni cache pozostaje dostępny, gdy dostawca jest offline; cache nie jest przedstawiany jako potwierdzenie bieżącej dostępności.
- Lista wyboru pokazuje wyłącznie modele z potwierdzonym darmowym statusem. Niezweryfikowane modele są liczone, ale zablokowane.
- Failover w `TeamRunEngine` używa tej samej restrykcyjnej reguły i nie przełącza na Groq/Gemini/Mistral ani inny model chmurowy bez jawnego potwierdzenia darmowego identyfikatora.
- Zapis przypisania wykorzystuje istniejący profil dostawcy i jego klucz API. Aplikacja nie zapisuje pustego profilu jako poprawnie przypisanego modelu.

## Czat
- Naprawiono tworzenie nowego czatu: nowy identyfikator jest zapisywany jako ostatnia rozmowa przed odtworzeniem widoku, więc aplikacja nie wraca do poprzedniej rozmowy.
- Pole wpisywania obsługuje wygodnie wielowierszowy tekst (do 6 linii przed przewijaniem) i akcję „Wyślij” z klawiatury.

## Ikona
- Przeprojektowano ikonę adaptacyjną: pięć odrębnych węzłów AI połączonych z centralnym rdzeniem MASTER, w ciemnej stylistyce z pomarańczowym i turkusowym akcentem.

## Weryfikacja
- Sprawdzono poprawność składni XML zasobów ikony.
- Dodano testy jednostkowe reguł darmowości i blokowania niezweryfikowanych modeli.
- Nie uruchamiano Gradle ani kompilacji Androida, zgodnie z ustaleniem — kompilację należy wykonać lokalnie w Android Studio.

## Ważne ograniczenie
Endpoint `/models` większości dostawców zwraca listę identyfikatorów, a nie indywidualną cenę, plan konta i pozostały limit. Dlatego aplikacja celowo nie oznacza automatycznie modeli Groq/Gemini/Mistral jako darmowych. Żeby rozszerzyć te grupy bez ryzyka, potrzebne są osobne, wiarygodne źródła cen/entitlements dla każdego dostawcy.
