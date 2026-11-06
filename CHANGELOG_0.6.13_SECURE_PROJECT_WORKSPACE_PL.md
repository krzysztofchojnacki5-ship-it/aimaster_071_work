# AI MASTER PRO 0.6.13 — Bezpieczny obszar roboczy projektu

## Co dodano
- Import archiwum ZIP do prywatnego katalogu aplikacji, bez modyfikowania źródłowych plików ani instalacji aplikacji.
- Ochronę przed ZIP Slip, ścieżkami absolutnymi, ścieżkami Windows, traversal (`..`), pustymi segmentami, duplikatami ścieżek (również z różną wielkością liter) i nadmierną głębokością.
- Limity: 40 MB archiwum, 80 MB rozpakowanej zawartości, 8 MB na plik, 3000 wpisów.
- Odfiltrowanie katalogów i plików typowo zawierających sekrety lub artefakty środowiska (`.git`, `.gradle`, `build`, `.env*`, `local.properties`, klucze i magazyny certyfikatów).
- Manifest plików z rozmiarem i SHA-256, widoczny z Centrum.
- Automatyczną kopię poprzedniego obszaru roboczego przed podmianą oraz retencję maksymalnie 5 kopii.
- Testy jednostkowe importu, ZIP Slip, filtrowania sekretów/artefaktów, duplikatów, kopii zapasowej i pustego archiwum.

## Granice funkcji
- Importowane pliki są przechowywane lokalnie i nie są wykonywane.
- Ten etap nie daje agentom możliwości zapisu do workspace, nie generuje patchy i nie stosuje zmian do kodu.
- Przywracanie kopii, diff, zatwierdzanie i uruchamianie testów projektu pozostają kolejnymi etapami.
