# Pakiet 23 — widoczność kosztów i gotowości agentów

Baza: `9df2c93493e9f4cdd6f7ede5661c7cc0458a1ab8` (pakiet 22).
Gałąź: `work/aiforteca-0.2-package-23-agent-cost-visibility`.
Kod objęty CI: `5b24d9aec384ac2e053fbb67300cde0bba25e125` (commit wyzwalający CI: `4e6bca85af9d7580f26090214ee7b756aebab9a6`).

## Zakres
1. Lista przydziału agentów pokazuje nazwę, kategorię, odpowiedzialność, rolę, dostawcę/model i status kosztowy.
2. Klasyfikacja statusu jest deterministyczna: potwierdzony darmowy, nieznana cena (z/bez zgody), brak konfiguracji, niegotowy, cena płatna lub niezweryfikowana.
3. Przed uruchomieniem zespół jest blokowany bez HTTP, a komunikat wylicza konkretnych agentów, role i profile wymagające poprawy.
4. Dodano testy jednostkowe klasyfikacji statusu.

## Ochrona kosztów
Nie zmieniono reguł `isProviderAllowedForRouting`, nie dodano automatycznych zgód i nie zmieniono przypisań dostawców. Status `UNKNOWN_PRICE_APPROVED` jest wyraźnie oznaczony jako niepotwierdzony darmowy; uruchomienie pozostaje możliwe wyłącznie dlatego, że użytkownik wcześniej zapisał wymaganą zgodę.

## Weryfikacja CI — 2026-11-19
- Unit Tests: run `44600597221` — PASS.
- Gradle Wrapper Validation: run `44600597246` — PASS.
- Android Build: run `44600597338` — PASS.
- Raport XML: 127 zestawów testowych, 1349 testów, 0 failures, 0 errors, 0 skipped.
- `TaskAgentAvailabilityTest`: 4/4 PASS.
- `assembleRelease`: PASS; wydanie opublikowano jako unsigned, ponieważ podpisywanie zostało pominięte.
- Debug APK: artifact `14567883778`, 2,197,029 B; SHA-256 `232b4cdc6efcbe6f86e57a57cf5d51ceec7b01838a8237dfab503254debf0041`.
- Release unsigned APK: artifact `14567659244`, 1,460,808 B.
- Source project: artifact `14567749038`, 1,535,433 B.
- Raporty testowe: artifact `14567603734`.
- Debug APK pobrano i sprawdzono jako poprawne archiwum APK z `AndroidManifest.xml`.

## Test ręczny
Oczekuje na instalację i sprawdzenie przez użytkownika. W szczególności należy sprawdzić opis agentów i statusy cenowe na liście przydziału oraz blokadę uruchomienia dla profilu niegotowego / niezatwierdzonej ceny nieznanej. Nie należy uznawać statusu za potwierdzenie aktywnego połączenia z dostawcą — pokazuje on konfigurację i politykę cenową.

## Status pakietu
CI, testy, build i publikacja artefaktów: PASS.
Pakiet czeka wyłącznie na akceptację ręczną na telefonie. Pakiet 24 nie powinien ruszyć przed tą weryfikacją.
