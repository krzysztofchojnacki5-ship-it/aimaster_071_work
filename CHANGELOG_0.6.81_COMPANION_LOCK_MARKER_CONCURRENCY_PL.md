# CHANGELOG 0.6.81-SOURCE — współbieżne publikowanie markera blokady

- Baza: 0.6.80-SOURCE.
- Zmieniono publikację pliku blokady Companion: każdy zapis otrzymuje unikalny plik tymczasowy z `Files.createTempFile`; finalny marker nadal jest publikowany przez `ATOMIC_MOVE` + `REPLACE_EXISTING`.
- Sprzątanie pliku tymczasowego zachowuje pierwotny wyjątek i dopina ewentualny błąd cleanup jako `suppressed`.
- Dodano test Robolectric dwóch instancji store wykonujących 32 równoległe zapisy markera; sprawdza treść pliku końcowego i brak osieroconych plików tymczasowych.
- Test dodany, ale nieuruchomiony: Gradle/Robolectric pozostają NOT RUN.
- Nie zmieniono wersji aplikacji, Keystore policy, transportu ani aktywacji listenera.
