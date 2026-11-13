# AL MASTER PRO 0.3.0 — Agent Engine Foundation

## Zmiany
- Zachowano dotychczasowy projekt Android, interfejs, konfiguracje dostawców i historię.
- Dodano jawne mapowanie specjalistów katalogu na istniejące profile ról (Diagnosta, Badacz, Programista, Recenzent, Twórca).
- Specjalista korzysta z przypisanego profilu roli, jeśli profil jest kompletny i sklasyfikowany jako darmowy/lokalny; w przeciwnym razie używa wybranego profilu MASTER, który nadal podlega kontroli darmowego modelu w silniku.
- Silnik tworzy pulę wątków dopasowaną do liczby faktycznie uruchamianych zadań.
- Specjalista może przełączyć się na darmowy profil MASTER jako zapasowy; rzeczywiste zużycie tokenów jest przypisywane do modelu, który faktycznie wykonał zapytanie; płatne/niezweryfikowane profile są odrzucane przez drugą kontrolę przed wywołaniem API. Kontrola korzysta z potwierdzonej ceny zero z katalogu modeli, jeśli dostawca udostępnia takie dane, oraz z konserwatywnej listy darmowych modeli.
- Główna ścieżka czatu pomija profil MASTER, jeśli jego darmowy status nie został potwierdzony; w takim przypadku korzysta wyłącznie z potwierdzonych darmowych profili zapasowych albo zatrzymuje żądanie.
- Ponawianie żądań HTTP ograniczono do bezpiecznych operacji GET; wywołania czatu POST nie są automatycznie powtarzane po niejednoznacznym błędzie sieci. Klucz Gemini jest kodowany w URL.
- Usunięto dodatkowe, ślepe ponawianie całego wywołania agenta po błędzie. Klient HTTP nadal ma własną ograniczoną obsługę przejściowych błędów; unikamy ponownego płatnego żądania po niejednoznacznym timeout.
- Poprawiono instrukcję kontekstu: agent nie może już zakładać, że widzi całe repozytorium, jeśli przekazany kontekst jest częściowy.
- Zwykła rozmowa nadal pozostaje wyłącznie po stronie MASTER; automatyczny routing uruchamia maksymalnie jednego specjalistę albo do trzech w zadaniu wielodziedzinowym, a do czterech tylko po jawnym żądaniu zespołu.

## Ważne ograniczenia
- To lokalny silnik orkiestracji Kotlin/Android, a nie pełny LangGraph. Nie wymaga serwera ani stale włączonego komputera.
- Odkrycie identyfikatora modelu przez endpoint `/models` nie dowodzi darmowego dostępu ani dostępnego limitu. Status FREE pozostaje konserwatywny i wymaga jawnego potwierdzenia.
- Brak kompilacji APK w tym środowisku oznacza, że paczka wymaga końcowej kompilacji i testów w Android Studio. Nie deklarujemy ich jako zaliczonych.
