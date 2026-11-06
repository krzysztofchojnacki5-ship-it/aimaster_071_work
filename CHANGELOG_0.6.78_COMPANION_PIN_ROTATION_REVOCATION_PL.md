# 0.6.78-SOURCE — rotacja pinów i unieważnianie aktywnych sesji

## Zmiana
- Etapowanie nowego SPKI zachowuje okres nakładania: stary i nowy pin są akceptowane, a istniejące sesje pozostają aktywne.
- Promocja staged pin jest zmianą granicy zaufania. Przed zapisem wykonywany jest peer-scoped registry fence, który natychmiast blokuje stare bilety admission i odłącza aktywne uchwyty tego peera.
- Trwały zapis nowego stanu odbywa się pod monitorem store; zamykanie socketów i publikacja observerów następują po zwolnieniu monitora.
- Błąd przygotowania fence lub zapisu nie pozostawia starej sesji aktywnej jako pozornego sukcesu: ścieżka wykonuje cleanup/dispatch i zgłasza błąd.
- Anulowanie staged pin pozostawia bieżące zaufanie bez zmian.

## Walidacja
- Kotlin `-Werror` wszystkich produkcyjnych klas Companion core: PASS.
- Nowe testy semantyki pinów: 3/3 PASS w lokalnym shim-JUnit runnerze.
- Android store, Gradle, Robolectric, Android Keystore i urządzenie: NOT RUN.
- Transport nie jest uruchamiany.
