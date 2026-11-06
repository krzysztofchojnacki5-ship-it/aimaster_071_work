# Plan UI: zakładka Modele, czat, limity tokenów

Status: częściowo zrobione (logika + przycisk). Wygląd NIE zweryfikowany (NOT RUN).

## 1. Limity tokenów w Diagnostyce — ZROBIONE (przycisk)
- Dialog `showLimitsDialog` już istniał (Monitor API → „Skonfiguruj limity”). Od 0.6.54 przycisk „Zmień limity tokenów” jest też bezpośrednio w Diagnostyce.
- Zapis: `ApiTokenTracker.setLimits`. Wartości poniżej 1 są podnoszone do 1 (dzienny i per agent); koszt ujemny do 0. To zachowanie jest celowe, ale użytkownik nie dostaje o tym informacji. Do poprawy: komunikat przy korekcie.

## 2. Zakładka Modele: grupy dostawców na górze — LOGIKA ZROBIONA, UI DO WDROŻENIA
- Logika: `core/ModelGrouping.groupByProvider(List<FreeModelSpec>)` (test: `ModelGroupingAndCopyableTermsTest`).
- Miejsce wdrożenia: `showProviders()` (ekran „Modele”). Sekcja „Wczytane modele” na górze, potem po jednym nagłówku na dostawcę (`providerName`) z listą `modelId`.
- Do ustalenia przy wdrożeniu: czy dane z katalogu (`FreeModelsCatalog.loadCatalog`) mają być pokazywane obok zapisanych profili, czy zastępować je.

## 3. Czat: czytelne odpowiedzi i kopiowanie pojęć — LOGIKA ZROBIONA, UI DO WDROŻENIA
- Logika: `core/CopyableTerms.extract(text)` zwraca `code` spans i linki w kolejności wystąpienia, bez duplikatów (test jw.).
- Istniejące elementy: `messageActions` (ikona kopiowania całej odpowiedzi), `renderMarkdown` / `addRichText` (nagłówki, listy, kod, tabele, cytaty).
- Do wdrożenia: pod każdą odpowiedzią Mastera rząd „chipów” z wartościami z `CopyableTerms.extract`; każdy chip kopiuje swoją wartość. Ważne pojęcia (pogrubienia) — do sprawdzenia, czy `addRichText` już je wyróżnia.
- Nie wdrożono: zmiany w `addRichText` ani w układzie wiadomości. To najbardziej wrażliwy fragment UI i wymaga zobaczenia na urządzeniu.

## 4. Kolejność wdrożenia (po zielonych testach)
1. Testy z `ModelGroupingAndCopyableTermsTest` i z `docs/INSTRUKCJA_URUCHOMIENIA_W_DOMU_PL.md`.
2. Grupowanie w `showProviders` (najmniejsze ryzyko).
3. Chipy kopiowania pod odpowiedziami w czacie.
4. Ocena wyglądu na telefonie; poprawki.
