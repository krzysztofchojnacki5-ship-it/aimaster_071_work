# AiFORTeca 0.2 — checkpoint pakietu 28: uczenie jakości routingu

Data: 2026-11-22

- Repozytorium: `krzysztofchojnacki5-ship-it/aimaster_071_work`
- Branch: `work/aiforteca-0.2-master-agent-foundation`
- PR: #3 (OPEN/DRAFT; bez zmian metadanych PR)
- Baza pakietu: `409ba6097e4182b958cb511c4c2c7d707ad3df3c`
- Commit kodu pakietu: `931992d92a23abf0716581afca2ba3ad4100b42a`

## Cztery poprawki

1. Do danych jakości agentów włączono oceny `CHAT_ANSWER`, które wcześniej były zapisywane, ale pomijane przez routing oczekujący wyłącznie `AGENT`.
2. Dodano wspólny, lokalny projekcyjny helper `agentQualityEvidenceByAgent`; identyfikatory agentów są normalizowane do małych liter.
3. Odrzucono z danych treningowych niepowiązane typy ocen, puste identyfikatory i oceny spoza `+1/-1`.
4. Zachowano wymóg niezależnych zadań: ten sam task oceniony jako `AGENT` i `CHAT_ANSWER` nie zwiększa sztucznie liczby próbek. Dodano cztery testy regresyjne.

## Granice bezpieczeństwa

- Uczenie pozostaje lokalne i deterministyczne.
- Oceny są wyłącznie sygnałem pomocniczym rankingu; nie nadpisują dopasowania kompetencji.
- Nie zmieniają automatycznie promptów, przypisań modeli ani pamięci zatwierdzanej przez użytkownika.
- Nie zmieniono wersji aplikacji, konfiguracji dostawców, kluczy, base branch ani stanu PR.
- APK nie jest warunkiem dalszych prac; ten pakiet nie żąda Android Build.

## Weryfikacja

- Testy CI i Gradle Wrapper Validation: PENDING dla końcowego HEAD pakietu.
- Wyników testów nie deklarować przed odczytem runów i XML.
- APK/build Android: NOT REQUESTED / NOT RUN.
- Test urządzenia: NOT RUN.

Następny etap po zielonym CI: audyt czy wyniki peer-review oraz oceny raportów zespołu mogą być mapowane na niezależne, niepodwójnie liczone dowody jakości — bez mieszania skuteczności technicznej z oceną merytoryczną.
