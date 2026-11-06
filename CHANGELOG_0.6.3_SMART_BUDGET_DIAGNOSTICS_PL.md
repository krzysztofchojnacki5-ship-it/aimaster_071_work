# 0.6.3 — Smart Budget i diagnostyka

## Zmiany
- Dodano dostępną zakładkę „Diagnostyka”; wcześniej `showDiagnostics()` istniało, ale nie było osiągalne z nawigacji.
- Ekran diagnostyczny pokazuje zapisane zużycie tokenów z bieżącego dnia, aktywne rezerwacje, pozostały limit dzienny, limit agenta oraz koszt z ostatnich 30 dni.
- Dodano kopiowanie raportu diagnostycznego bez ujawniania kluczy API.
- Komunikat blokady Smart Budget pokazuje teraz rzeczywisty powód (limit dzienny, limit agenta lub kosztowy) oraz stan dziennego budżetu zamiast ogólnego komunikatu.
- Dodano test jednostkowy sprawdzający, czy podsumowanie budżetu uwzględnia zarówno zużycie zapisane, jak i aktywne rezerwacje.

## Ważne
Darmowe modele nadal zużywają tokeny i mogą podlegać limitom dostawcy. Zerowy koszt API nie oznacza nielimitowanego użycia. Zmiany nie wyłączają bezpieczników Smart Budget ani automatycznie nie zwiększają limitów.

## Weryfikacja
Testów po zmianach nie udało się uruchomić w środowisku roboczym, ponieważ Gradle Wrapper wymaga pobrania Gradle 8.13, a środowisko nie ma dostępu DNS do `services.gradle.org`. Archiwum wejściowe zawierało wcześniejsze wyniki 92 testów bez błędów; nie są one dowodem kompilacji niniejszych zmian.
