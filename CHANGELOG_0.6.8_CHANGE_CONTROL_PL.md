# AI MASTER PRO 0.6.8 — Kontrola zmian

- Dodano ChangeSafetyGuard do walidacji względnych ścieżek, blokowania traversal, ścieżek chronionych, duplikatów, zbyt dużych zmian i znanych wzorców sekretów.
- Dodano lokalny ChangeSetStore z kontrolowanym cyklem PROPOSED → APPROVED → APPLIED → ROLLED_BACK oraz rejestrem wyników testów.
- Niedozwolone jest oznaczenie zmiany jako APPLIED bez uprzedniej akceptacji.
- Dodano ekranowy podgląd rejestru propozycji zmian.
- Ważne ograniczenie: bieżąca aplikacja nie ma jeszcze bezpiecznego mechanizmu zapisu plików projektu; moduł stanowi walidowaną warstwę kontroli i dziennik, nie wykonuje automatycznego patchowania ani backupu workspace.
