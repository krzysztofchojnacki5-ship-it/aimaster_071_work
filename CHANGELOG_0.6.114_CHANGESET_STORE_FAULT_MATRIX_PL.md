# CHANGELOG — 0.6.114-SOURCE

Baza: 0.6.113-SOURCE.

## Zmiany
- Dodano hostowy fault harness dla produkcyjnych `ChangeSetStore` i `HostActiveChangeSetStore`.
- Weryfikacja read exception i corrupt JSON: ledger przechodzi w CORRUPT, mutacje są blokowane, a istniejące dane nie są nadpisywane.
- Weryfikacja `write=false` i wyjątku zapisu: operacja nie raportuje sukcesu, poprzednia wartość pozostaje zachowana w modelu storage.
- Weryfikacja zapisu active pointer: błąd commit jest raportowany; ponowienie po usunięciu awarii kończy się sukcesem.
- Weryfikacja invalidation: `remove=false` i wyjątek nie są ukrywane; ponowienie usuwa pointer.
- Weryfikacja, że corrupt ledger nie może autoryzować nowego active pointer.

## Testy
- Store fault matrix 10/10 PASS.
- Recovery coordinator 13/13 PASS.
- Commit gate 7/7 PASS.
- Recovery policy 11/11 PASS.
- Receipt protocol 8/8 PASS.
- Razem 49 hostowych przypadków PASS.

## Granice dowodów
Testy magazynów uruchamiają produkcyjne klasy na host JVM z fake `StringPreferenceStore` oraz shimami Context/JSON. Nie zastępują Robolectric ani testu Android SharedPreferences. Gradle, Android build, APK, urządzenie i process-death NOT RUN. Companion transport BLOCKED.
