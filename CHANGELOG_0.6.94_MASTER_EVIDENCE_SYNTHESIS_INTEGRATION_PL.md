# 0.6.94 — Master Evidence Synthesis Integration

## Cel
Podłączyć `VerificationEvidenceResolver` do rzeczywistych ścieżek odpowiedzi MASTER-a, bez poszerzania zakresu i bez fallbacku do najnowszych/niepowiązanych danych.

## Zmiany
- Dodano `MasterEvidenceContextBuilder` jako warstwę selekcji pomiędzy ChangeSetStore, VerificationEvidenceResolver i promptem MASTER-a.
- Kandydat musi należeć do aktywnego czatu i zawierać `changeSetId`, `taskId` oraz `snapshotId`.
- Selekcja wymaga jawnego odwołania w prompt użytkownika do dokładnego `changeSetId`, `taskId` lub `snapshotId`. Wiele dopasowań jest odrzucane jako ambiguous.
- Brak jawnego odwołania nie wybiera najnowszego wpisu. Brakujący identyfikator nie jest uzupełniany sztucznie.
- Resolver zwraca wyłącznie exact scope task/chat/snapshot. Kontekst zawiera status assessment, wymagane/brakujące/nierozstrzygnięte/nieudane rodzaje dowodów, bezpieczne rekordy, liczbę claimów i odrzuconych wykonań.
- Kontekst został podłączony do MASTER_ONLY oraz team synthesis. `MasterResponseCheck` otrzymuje ten sam assessment, który widzi MASTER.
- Brak assessmentu pozostaje `null`, więc twierdzenia o testach/kompilacji/buildzie nie mogą zostać uznane za potwierdzone.

## Walidacja
- 5 nowych testów `MasterEvidenceContextBuilderTest`.
- Łącznie 57/57 lokalne testy shim-JUnit PASS.
- `kotlinc -Werror` PASS przez `tools/verification_resolver_harness/run.sh`.
- Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN.

## Ograniczenie i następny krok
Selekcja jest bezpieczna, ale wymaga jawnego identyfikatora w prompt użytkownika. W 0.6.95 należy przenieść wybór scope do hostowego kontekstu aktywnego zadania/orchestratora (z jawnie ustanowionym ChangeSet/snapshot), tak aby MASTER nie musiał parsować identyfikatorów z tekstu użytkownika. Wciąż nie wolno wybierać „najnowszego” bez reguły aktywnego zadania.
