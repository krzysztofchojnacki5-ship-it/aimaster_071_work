# Kontrakt zakresu dowodów MASTER-a — 0.6.90

Każdy rekord dowodu może być przypisany do trzech niezależnych wymiarów:

- `taskId` — konkretna jednostka pracy (np. ChangeSet / zadanie wykonawcze),
- `chatId` — rozmowa, w której zadanie zostało zlecone,
- `snapshotId` — dokładny stan prywatnego workspace, którego dotyczy kontrola.

## Reguły

1. Ocena nie może mieszać dowodów o różnych `taskId`, `chatId` lub `snapshotId`.
2. MASTER może otrzymać dowody tylko przez exact-scope lookup z trzema niepustymi identyfikatorami.
3. Nie wolno wybierać dowodów przez „najnowszy rekord”, nazwę pliku, podobieństwo promptu ani sam `taskId`, jeśli zadanie wymaga również konkretnego czatu i snapshotu.
4. Rekordy V1 są zachowywane, ale nie mają retroaktywnie dopisywanego czatu ani snapshotu. Nie mogą wejść do exact-scope lookup.
5. `snapshotId` nie zastępuje `taskId`. To różne identyfikatory i nie wolno ich zamieniać.
6. `chatId` jest kontekstem pochodzenia, nie dowodem prawdziwości.
7. `executionId` musi być nadawany przez zaufany host registry; pole przekazane przez model lub raport agenta nie jest dowodem uruchomienia narzędzia.
8. Brak lub niezgodność identyfikatora kończy się pustym/odrzuconym wynikiem, nigdy fallbackiem do innego zakresu.

## Granice 0.6.90

Wydanie wiąże metadane i przygotowuje bezpieczny odczyt. Nie podłącza jeszcze hostowego rejestru wywołań ani nie przekazuje automatycznie dowodów do syntezy MASTER-a.
