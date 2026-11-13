# AL MASTER PRO 0.3.7 — Centrum jakości i kontrolowane uczenie

## Nowości
- Lokalny system ocen 👍/👎 dla końcowych odpowiedzi czatu oraz raportów agentów.
- Jedna aktywna ocena na element; ponowna ocena aktualizuje wynik, a poprzednie oceny i komentarze pozostają w historii audytowej.
- Oceny są wiązane z identyfikatorem zadania, agentem, providerem i modelem.
- Dziennik wykonania zapisuje identyfikator zadania oraz tokeny wejściowe/wyjściowe, jeśli API zwróciło takie dane. Stare wpisy są zgodne wstecz (pola domyślne).
- Nowy ekran Centrum jakości i uczenia się: podsumowanie ocen, ręczna ocena ostatnich przebiegów i przegląd propozycji.
- Analiza jakości przez MASTER-a uruchamia się wyłącznie ręcznie, po potwierdzeniu kosztu tokenów. Statystyki i oceny lokalne nie wykonują wywołań API.
- Propozycje uczenia mają status PROPOSAL / APPROVED / REJECTED, liczbę dowodów, poziom pewności, zadania źródłowe, agentów i modele.
- Do pamięci wspólnej trafiają wyłącznie zatwierdzone propozycje. Cofnięcie zatwierdzenia usuwa odpowiadającą mu regułę z pamięci.
- MASTER otrzymuje agregaty ocen agentów i modeli z ostrzeżeniem o małej próbie: poniżej 5 ocen bez mocnych wniosków, 5–9 wstępnie, 10+ większa pewność.

## Bezpieczeństwo i kompatybilność
- Nie zapisujemy promptów, pełnych odpowiedzi ani kluczy API w dzienniku jakości.
- Nie zmieniamy automatycznie przypisań modeli, providerów ani routingu.
- Dane 0.3.6 są zachowane; nowe pola dziennika są opcjonalne przy odczycie starych wpisów.
- Oceny/propozycje są przechowywane lokalnie w osobnym SharedPreferences i nie są usuwane przy czyszczeniu dziennika operacyjnego.

## Weryfikacja
- Dodano testy Robolectric dla edycji ocen i historii audytowej, zatwierdzania/odrzucania/cofania propozycji oraz zapisu tokenów i identyfikatora zadania.
- `./gradlew testDebugUnitTest` nie mógł się uruchomić: wrapper nie pobrał Gradle 8.13 z `services.gradle.org` z powodu `UnknownHostException`. Build i testy pozostają niepotwierdzone.
