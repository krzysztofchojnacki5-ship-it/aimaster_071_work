# AI MASTER PRO 0.6.99-SOURCE — ochrona rollbacku tożsamością workspace

## Problem wykryty w audycie 0.6.98

Weryfikacja kończyła sekcję krytyczną przed rozpoczęciem automatycznego rollbacku. W tym oknie mogła nastąpić wymiana workspace przez import ZIP. Bez dodatkowej kontroli rollback mógł przywrócić snapshot poprzedniego projektu na miejsce świeżo zaimportowanych źródeł.

## Naprawa

- Tożsamość manifestu jest pobierana bezpośrednio przed weryfikacją, a cały runner działa w tej samej sekcji globalnej blokady.
- `ProjectWorkspaceIdentityGuard.requireUnchanged()` wymaga niepustego i identycznego SHA-256 manifestu przed destrukcyjnym restore.
- Zmiana workspace między weryfikacją i rollbackiem blokuje restore.
- Import ZIP i invalidation active evidence scope są liniaryzowane pod jedną blokadą; patch aktywowany po imporcie nie zostanie przypadkowo wyczyszczony przez opóźniony callback importu.
- Awaria czyszczenia active pointer nie fałszuje wyniku importu/restore. Błąd jest jawnie raportowany, a mismatch identity nadal blokuje użycie starego zakresu.

## Testy

- Dodano testy exact identity, mismatch, missing identity, lock release po wyjątku oraz wyścigu import-invalidation kontra aktywacja nowego patcha.
- Resolver/lock harness: 73/73 PASS.
- ChangeSet/HostActive store compile shim: `kotlinc -Werror` PASS.
- Gradle/JUnit/Robolectric/Android build/APK/device: NOT RUN.
