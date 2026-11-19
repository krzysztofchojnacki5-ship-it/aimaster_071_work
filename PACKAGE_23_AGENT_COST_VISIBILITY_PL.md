# Pakiet 23 — widoczność kosztów i gotowości agentów

Baza: `9df2c93493e9f4cdd6f7ede5661c7cc0458a1ab8` (pakiet 22).

## Zakres
1. Lista przydziału agentów pokazuje nazwę, kategorię, odpowiedzialność, rolę, dostawcę/model i status kosztowy.
2. Klasyfikacja statusu jest deterministyczna: potwierdzony darmowy, nieznana cena (z/bez zgody), brak konfiguracji, niegotowy, cena płatna lub niezweryfikowana.
3. Przed uruchomieniem zespół jest blokowany bez HTTP, a komunikat wylicza konkretnych agentów, role i profile wymagające poprawy.
4. Dodano testy jednostkowe klasyfikacji statusu.

## Ochrona kosztów
Nie zmieniono reguł `isProviderAllowedForRouting`, nie dodano automatycznych zgód i nie zmieniono przypisań dostawców. Status `UNKNOWN_PRICE_APPROVED` jest wyraźnie oznaczony jako niepotwierdzony darmowy; uruchomienie pozostaje możliwe wyłącznie dlatego, że użytkownik wcześniej zapisał wymaganą zgodę.

## Weryfikacja
Status testów, Android build i artefaktów: NOT RUN — oczekuje na CI.
Ręczny test na telefonie: NOT RUN.
