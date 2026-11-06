# AI MASTER PRO 0.6.100-SOURCE — transakcyjność preview/apply i kompensacja

## Wykryte ryzyka
1. Preview i peer review mogły trwać podczas importu innego projektu. Kontrola samych hashy zmienianych plików nie wiązała całego preview z jednym manifestem; patch mógł trafić do nowego workspace o częściowo identycznych plikach.
2. Chat ID było pobierane dopiero przy potwierdzeniu apply, więc długotrwała recenzja i przełączenie czatu mogły zmienić kontekst przypisania ChangeSet.
3. Awaria `recordSnapshot()` lub przejścia statusu po fizycznym apply pozostawiała zmieniony workspace bez bezpiecznej kompensacji.
4. `ProjectWorkspaceStore.applyPreview()` nie przywracał snapshotu, jeśli błąd wystąpił po pomyślnej podmianie katalogu, ale przed zwróceniem `ApplyResult`.

## Naprawy
- Preview utrwala workspace SHA-256 i chatId; obie wartości są przekazywane przez peer review do finalnego potwierdzenia.
- Apply ponownie sprawdza chatId i workspace identity, a następnie wykonuje integralność + patch pod jednym process lock.
- Zapis snapshotu, statusu i active pointer ma jawne flagi etapów. Błąd przed trwałym `APPLIED_UNVERIFIED` uruchamia kompensacyjny rollback z identity guard i próbę uzgodnienia ledgeru.
- Błąd samej aktywacji scope po trwałym `APPLIED_UNVERIFIED` nie powoduje wycofania zaakceptowanego patcha; raportuje patch jako zastosowany bez aktywnego zakresu dowodów.
- `applyPreview()` wykonuje wewnętrzne przywrócenie snapshotu po błędzie post-swap; w razie niepowodzenia recovery zgłasza osobny, jawny błąd z pierwotnym wyjątkiem jako przyczyną.
- Snapshot kompensacyjny jest chroniony przed pruningiem do zakończenia kontroli post-swap.

## Testy
- Resolver/lock harness: 73/73 PASS.
- ChangeSet/HostActive compile shim: PASS.
- Workspace core `kotlinc -Werror`: PASS.
- JVM smoke: import → preview → apply → restore → stale preview rejection: PASS.
- Gradle/JUnit/Robolectric/Android build/APK/device: NOT RUN.
