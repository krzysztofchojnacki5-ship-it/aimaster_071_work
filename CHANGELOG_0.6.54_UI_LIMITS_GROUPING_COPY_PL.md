# Changelog — AI MASTER PRO 0.6.54

## Limity tokenów
- Przycisk „Zmień limity tokenów” w Diagnostyce (wywołuje istniejący `showLimitsDialog`). Limity były już edytowalne w Monitor API; zmiana dodaje bezpośredni dostęp.

## Logika (bez zmian w widocznym UI)
- `core/ModelGrouping`: grupowanie modeli po dostawcach, sortowanie po nazwie dostawcy i ID modelu.
- `core/CopyableTerms`: wyciąganie fragmentów `kod` i linków z odpowiedzi do kopiowania.
- Testy: `ModelGroupingAndCopyableTermsTest` (6 testów).

## Granice
- Grupowanie w zakładce Modele i chipy kopiowania w czacie NIE są podłączone do UI (plan w `docs/UI_PLAN_MODELE_CZAT_PL.md`).
- Kod nie został skompilowany ani uruchomiony (NOT RUN).
