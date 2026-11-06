# CHANGELOG 0.6.130 — staged import durable recovery model

- Rozszerzono hostowy model staged import z 12 do 20 przypadków.
- Dodano jawną maszynę stanów `PREPARED → COMMITTING → SWAPPED_AWAITING_EVIDENCE → COMMITTED`, z odgałęzieniem `ABORTED`.
- Dodano symulację restartu dla orphan PREPARED, commit bez swapu, wyniku OLD_CURRENT, wyniku NEW_CURRENT i oczekującej invalidacji evidence.
- Sprawdzono idempotencję recovery, invalidacji oraz terminalność stanów.
- Dodano `STAGED_IMPORT_DURABLE_RECOVERY_MODEL_0.6.130_PL.md` z regułami i ograniczeniami.
- Produkcyjny importer pozostaje nietknięty; testy lifecycle są modelem in-memory, nie testem trwałości plików/Android.
- Wersja aplikacji bez zmian: 0.6.54 / code 80. Companion Transport BLOCKED.
