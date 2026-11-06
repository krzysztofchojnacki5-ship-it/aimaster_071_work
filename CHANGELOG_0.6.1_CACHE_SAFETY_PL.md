# AI MASTER PRO 0.6.1 — bezpieczeństwo pamięci wyników

## Poprawki
- Fingerprint cache zawiera teraz identyfikator dostawcy, bazowy URL API i model. Różni dostawcy nie współdzielą raportów tylko dlatego, że używają tej samej nazwy modelu.
- Pola fingerprintu są kodowane z długością, co usuwa niejednoznaczność separatorów w treści promptu. Dodano wersję formatu klucza `task-cache-v2`, więc stare wpisy nie są błędnie dopasowywane.
- Wynik uzyskany przez fallback nie jest zapisywany pod kluczem przypisanego dostawcy. Dzięki temu późniejszy cache hit nie udaje, że raport pochodzi z innego API.
- Rezerwa syntezy jest liczona na podstawie faktycznej liczby agentów po ograniczeniu adaptacyjnym, a nie pierwotnej listy.

## Kompatybilność
- Nie zmieniono formatu konfiguracji, kluczy API, historii czatów ani backupu. Cache v1 pozostaje na urządzeniu, ale nie będzie trafiał w nowe klucze v2 i zostanie usunięty standardowo przy odczycie/wygaśnięciu lub może pozostać do czasu limitu TTL.
- Wersja aplikacji: 0.6.1 (versionCode 25).
