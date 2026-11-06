# AL MASTER PRO 0.3.8 — oszczędność tokenów i wiarygodność ocen

## Poprawki
- Analiza jakości MASTER-a otrzymuje skondensowany zestaw danych: agregaty według agentów, modeli i kategorii oraz maksymalnie 20 krótkich komentarzy. Kontekst jest ograniczony do 12 000 znaków.
- Identyfikator ocenianego elementu jest rozpatrywany łącznie z typem. Raport agenta i odpowiedź końcowa nie nadpisują sobie ocen nawet przy takim samym ID.
- Ponowna ocena bez ponownego przekazania metadanych zachowuje pierwotnego agenta, providera, model i kategorię.
- Wybór raportu do oceny pokazuje tylko przebiegi zakończone poprawnie; awarie API nie są przedstawiane jako odpowiedzi merytoryczne.
- Ocena końcowej odpowiedzi czatu może być zmieniona; interfejs pokazuje aktualnie wybraną ocenę.
- Dodano testy regresyjne dla typów ocen, zachowania metadanych oraz ograniczenia wielkości kontekstu analizy.

## Cel wydajnościowy
- Zbieranie statystyk, ocen i budowanie lokalnych rankingów nadal nie wykonuje wywołań API.
- Ręczna analiza MASTER-a dostaje mniej danych, bez pełnych odpowiedzi i promptów; komentarze są ograniczone.
- Nie dodano żadnego automatycznego wywołania AI ani automatycznego przełączania modeli.

## Weryfikacja
- ZIP sprawdzony po utworzeniu.
- Pełny build Android i testy Gradle nie zostały potwierdzone w tym środowisku: brak lokalnej dystrybucji Gradle 8.13 i brak dostępu sieciowego do pobrania wrappera.
