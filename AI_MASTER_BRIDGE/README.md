# AI MASTER PRO — Work Bridge

Wspólna, audytowalna kolejka pracy między ChatGPT MASTER, agentem Android Studio i GitHub.

## Zasady bezpieczeństwa

1. Agent Android Studio wykonuje wyłącznie zadanie jawnie opisane w `INBOX.md`.
2. Każde zadanie realizuje na osobnym branchu `bridge/task-<ID>`; nie pracuje bezpośrednio na `main` ani na branchach audytowych.
3. Agent nie wykonuje merge. Push i utworzenie PR są dozwolone dopiero po sprawdzeniu diffu i skanowaniu sekretów.
4. Nie wolno kopiować do raportów wartości zmiennych środowiskowych, API keys, tokenów, haseł, plików lokalnych ani zawartości `local.properties`.
5. Nie wolno deklarować PASS bez zapisanego kodu wyjścia i raportu testów. Timeout, anulowanie i brak uruchomienia oznaczają NOT VERIFIED.
6. Nie wolno tworzyć APK ani deklarować Android build PASS bez rzeczywistego `:app:assembleDebug` zakończonego kodem 0.
7. Przed rozpoczęciem agent zapisuje base commit SHA. Po pracy zapisuje head commit SHA, listę plików, testy, build i wynik skanowania sekretów.
8. Nie wolno zmieniać `INBOX.md` w ramach realizacji zadania. Status i raport zapisuje się w `REPORTS/`.

## Status możliwości

- Odczyt i zapis lokalnych plików przez agenta: potwierdzony audytem użytkownika.
- Odczyt i zapis GitHub przez lokalny git: potwierdzony konfiguracją origin; push konkretnego brancha należy weryfikować per sesja.
- Automatyczne cykliczne polling: niedostępne w trybie agenta IDE według audytu.
- Automatyczne testy: narzędzie IDE wykrywa zadania Gradle; rzeczywisty pełny test i build nie zostały jeszcze potwierdzone w raporcie audytu.

## Uruchomienie zadania

W Android Studio: odśwież branch `bridge/work-bridge-bootstrap`, przeczytaj `INBOX.md`, a następnie wykonuj wyłącznie zadanie o statusie `READY`. Nie wykonuj zadań oznaczonych `DRAFT`, `BLOCKED` lub `DONE`.
