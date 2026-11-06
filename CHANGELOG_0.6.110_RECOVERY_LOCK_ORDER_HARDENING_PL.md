# CHANGELOG — AI MASTER PRO 0.6.110-SOURCE

## Cel
Usunąć możliwość deadlocku wynikającą z odwrotnej kolejności monitora `MainActivity` i process-wide `ProjectWorkspaceOperationLock`, a następnie przetestować wyścigi chat switch / import / równoległe recovery.

## Zmiany produkcyjne
- `MainActivity.currentChatId` otrzymał `@Volatile`, aby wątki robocze widziały aktualnie wybrany czat bez przejmowania monitora aktywności.
- `isCurrentChat()` nie jest już `@Synchronized`. Może być bezpiecznie wywoływany z sekcji krytycznej workspace, nie tworząc cyklu: activity monitor → workspace lock → activity monitor.
- Pozostała logika aktywacji nadal weryfikuje exact chatId oraz exact workspace identity pod `ProjectWorkspaceOperationLock`.

## Nowe testy
- Deterministyczny lock-order harness: workspace lock zajęty przez wątek B, monitor aktywności przez wątek A; wątek B wykonuje check czatu bez blokowania i zwalnia workspace, po czym aktywacja kończy się bez deadlocku.
- Chat switch po reconcile, przed aktywacją: stary czat nie może zostać aktywowany.
- Dwa równoległe recovery callers: jeden rzeczywisty pending receipt jest uzgadniany i acknowledge dokładnie raz.
- Import rzeczywistego ZIP po przygotowaniu recovery: workspace identity się zmienia, a aktywacja starego receipt jest blokowana.

## Walidacja
- Recovery concurrency: 3/3 PASS + statyczny guard MainActivity PASS.
- Filesystem integration: 7/7 PASS.
- Coordinator: 9/9 PASS.
- Protocol: 8/8 PASS.
- Policy: 11/11 PASS.
- Workspace lock: 5/5 PASS.
- Verification resolver: 73/73 PASS.
- ChangeSet/HostActive compile shim: PASS.
- Razem 116 jawnych przypadków PASS w tej iteracji.
- Workspace transaction recovery harness: rerun zakończył się timeoutem podczas kompilacji; brak nowego wyniku (poprzedni wynik 0.6.109: 12/12 PASS, niezaliczany do bieżącej iteracji).
- Gradle/JUnit/Robolectric/Android build/APK/device: NOT RUN.

## Następny krok
- Przeprowadzić audyt wszystkich sekcji `ProjectWorkspaceOperationLock` pod kątem wywołań metod `@Synchronized` / `synchronized(this)` i potencjalnego lock inversion.
- Wydzielić testowalny seam dla granicy `reconcile → aktywacja → acknowledge`, aby móc wstrzykiwać chat switch/import dokładnie między tymi fazami.
- Uruchomić Robolectric/Gradle startup recovery, kiedy środowisko build będzie dostępne.
- Companion transport nadal BLOCKED; `versionName=0.6.54`, `versionCode=80`; bez APK.
