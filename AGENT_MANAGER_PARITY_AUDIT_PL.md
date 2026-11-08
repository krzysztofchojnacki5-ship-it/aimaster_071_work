# Audyt Parytetu Agent Manager (WB-008)

## 1. Wprowadzenie
Cel zadania WB-008 polega na zbudowaniu w AI MASTER PRO centrum zarządzania agentami (Agent Manager) o funkcjonalności porównywalnej z panelem Agent Manager w Android Studio / IDE, bez kopiowania kodu, binariów czy prywatnych API firm trzecich.

## 2. Analiza funkcji obserwowalnych w Android Studio Agent Manager
- **Lista i rejestr agentów:** Przegląd wszystkich dostępnych agentów specjalistycznych (`AgentCatalog`).
- **Statusy wykonania:** Obserwacja stanów `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.
- **Metryki zużycia:** Liczba użytych tokenów, czas trwania (`durationMs`), wyselekcjonowany provider oraz model LLM.
- **Logi i diagnostyka:** Ostatni wpis dziennika wykonania oraz komunikaty o błędach.
- **Kontrola cyklu życia:** Realne akcje sterujące (Stop / Pause / Resume) powiązane z silnikiem wykonawczym (`TeamRunEngine`).

## 3. Architektura Implementacji w AI MASTER PRO
- `AgentManagerModels.kt`: Modele danych reprezentujące statusy oraz wpisy wykonania agentów.
- `AgentManagerStore.kt`: Magazyn agregujący dane z katalogu agentów, rejestru zadań i licznika tokenów.
- `AgentManagerScreen.kt`: Interfejs użytkownika prezentujący podsumowanie oraz karty poszczególnych agentów z przyciskami akcji.
- `AgentManagerParityTest.kt`: Testy jednostkowe weryfikujące poprawność modelu, agregacji oraz obsługi akcji.
