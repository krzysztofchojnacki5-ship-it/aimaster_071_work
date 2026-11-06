# Changelog — dokumentacja 0.6.49.1 (bez zmian w kodzie)

## Punkt 2 — projekt migracji i przechowywania PSK
- Dodano `docs/PSK_MIGRATION_DESIGN_PL.md`: stan faktyczny, ograniczenie protokołu, model docelowy, stany, scenariusze migracji, zasady bezpieczeństwa, decyzje do podjęcia, plan testów.
- Ustalono z kodu: w wersji produkcyjnej nie ma producenta ani zapisu PSK; `MainActivity` nie używa parowania; `allowBackup=false`; eksport obejmuje tylko konfiguracje dostawców.
- Zgłoszono osobny problem w `SecureStore.key()` (usuwanie klucza przy każdym wyjątku) — do decyzji, nie zmieniono kodu.

## Granice
- Kod aplikacji nie został zmieniony; wersja aplikacji pozostaje 0.6.49 (versionCode 75).
