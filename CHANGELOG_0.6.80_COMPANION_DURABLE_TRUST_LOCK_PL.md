# 0.6.80-SOURCE — redundantna trwała blokada zaufania Companion

- Baza: 0.6.79-SOURCE.
- Rozszerzono fail-closed z process-local latch + Keystore alias deletion do trzech niezależnych barier: latch, marker SharedPreferences i marker plikowy w noBackupFilesDir.
- Marker plikowy jest publikowany atomowo po `fd.sync()`; sprawdzanie używa `readAttributes` z `NOFOLLOW_LINKS`, a nie `File.exists()`, aby błędu dostępu nie interpretować jako braku blokady.
- Odczyt markerów jest fail-closed także wtedy, gdy nie można wiarygodnie odczytać stanu blokady.
- `wipeAllTrust()` usuwa payload i marker preferences w jednym commicie, usuwa marker plikowy, a dopiero potem resetuje latch. Nieudane usunięcie pliku utrzymuje blokadę.
- Dodano testy Robolectric i lokalny fault harness; szczegóły oraz ograniczenia w `BUILD_STATUS_0.6.80_PL.md`.
- Nie zmieniono wersji aplikacji 0.6.54 / code 80. Nie utworzono APK. Transport nadal nieaktywny.
