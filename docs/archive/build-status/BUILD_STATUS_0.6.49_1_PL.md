# Status: dokumentacja punktu 2 (PSK) — bez zmian w kodzie

## Wynik
- Projekt zapisany w `docs/PSK_MIGRATION_DESIGN_PL.md`.
- Kod, build i testy: bez zmian (wersja aplikacji 0.6.49, versionCode 75).

## Otwarte decyzje (wymagane przed kodem)
1. Czy istniejący PSK istnieje poza repozytorium.
2. Model klucza: przejściowy (surowe PSK zaszyfrowane at-rest) czy docelowy (HMAC w Keystore).
3. Akceptacja poprawki `SecureStore.key()` (nie usuwać klucza przy każdym wyjątku).
