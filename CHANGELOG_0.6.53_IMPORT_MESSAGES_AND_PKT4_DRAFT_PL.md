# Changelog — AI MASTER PRO 0.6.53

## Poprawka komunikatów importu
- Komunikaty po błędzie importu konfiguracji nie obiecują już zachowania poprzednich danych, bo przy błędzie w połowie konfiguracje o tych samych ID mogły zostać nadpisane. Nowy tekst mówi, że część danych mogła się zmienić i że należy sprawdzić listę dostawców oraz ponowić import.

## Szkic pkt 4 — POZA BUILDEM
- `drafts/pkt4/CompanionConnectionRegistry.kt` i test: rejestr połączeń z epokami unieważnienia. Nie jest w `app/src`, nie wchodzi do kompilacji, nie został uruchomiony (NOT RUN).
- Wdrożenie dopiero po zielonych testach właściciela (patrz `drafts/pkt4/README_DRAFT_PL.md`).

## Granice
- Kod aplikacji zmieniony tylko w tekstach komunikatów; nie kompilowano (NOT RUN).
- Pkt 4 nie jest wdrożony w aplikacji.
