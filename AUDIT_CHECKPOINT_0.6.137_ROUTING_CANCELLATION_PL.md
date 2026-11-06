# AI MASTER PRO — checkpoint audytu 0.6.137
Data: 2026-11-06
Gałąź: `audit/0.6.137-routing-hardening`
Zakres: routing, scheduler, rozliczanie użycia API, timeouty i anulowanie agentów.

## Stan repozytorium
- Ostatni HEAD kodu przed aktualizacją tego checkpointu: `c69992b66380c40a03d094ebb952fb9aaa613102`
- HEAD `main`: `752149d40186144b0f458a32b5826c8928349c3a` (bez zmian).
- `main` nie zawiera zmian z tej gałęzi.
- Brak `.github/workflows/` oraz `gradle/wrapper/gradle-wrapper.jar`; nie potwierdzono możliwości uruchomienia builda w CI.

## Zmiany wykonane na gałęzi roboczej

### Routing i scheduler
1. `TeamRunEngine.kt`: odmowa lease przez lokalny scheduler/cooldown nie zapisuje już negatywnego wyniku kondycji dostawcy. Log `MODEL_THROTTLED` pozostaje.
2. `SmartBudgetAndHealthTest.kt`: poprawiono założenie testu rankingu — backup musi mieć obserwowane sukcesy, zgodnie z konserwatywną polityką `ModelHealthTracker`.
3. `TeamRunEngineNetworkTest.kt`: dodano regresję sprawdzającą, że cooldown schedulera nie wysyła HTTP i nie obniża oceny zdrowia providera.

### Rozliczanie i timeouty
4. `TeamRunEngine.kt`: limit 90 sekund jest liczony od wysłania zadania do puli, a nie od rozpoczęcia sekwencyjnego odbierania jego wyniku.
5. Po odpowiedzi API wykorzystanie jest rejestrowane przed walidacją treści. Błąd lokalnego zapisu usage jest jawnie logowany jako `USAGE_RECORD_FAILED` i nie uruchamia fallbacku, który mógłby ponowić płatne żądanie.
6. Po anulowaniu lub timeout odpowiedź, która dotarła z opóźnieniem, nie może zostać opublikowana jako poprawnie ukończony raport.

### Anulowanie pojedynczego agenta
7. `ProviderClient.RequestScope` otrzymał lokalny stan anulowania. Połączenia HTTP są mapowane do konkretnego zakresu żądania.
8. Dodano `childRequestScope(parent)` oraz `cancelRequestScope(scope)`. Anulowanie zakresu potomnego rozłącza tylko połączenia tego agenta; globalne `cancelActiveRequests()` nadal unieważnia wszystkie starsze zakresy aplikacji.
9. `TeamRunEngine` tworzy osobny zakres potomny dla każdego agenta. Po timeout najpierw anuluje jego zakres HTTP, a następnie przerywa Future.
10. Jawny błąd `ProviderException(category = CANCELLED)` jest traktowany jako anulowanie, a nie awaria providera; nie może uruchomić fallbacku.
11. `ProviderClientNetworkTest.kt`: dodano test równoległy, który anuluje jedno żądanie i sprawdza, czy drugie żądanie rodzeństwa kończy się poprawnie.

## Ograniczenia i status weryfikacji
- Zmiany zostały zapisane w repozytorium przez GitHub API i HEAD gałęzi zweryfikowano.
- Testy JUnit/Robolectric nie zostały uruchomione.
- Kompilacja Android/Gradle nie została uruchomiona.
- Brak workflow GitHub Actions i brak wrapper JAR uniemożliwiają obecnie potwierdzenie builda przez repozytorium.
- Wszystkie nowe testy mają status NOT RUN, nie PASS.
- Companion Transport pozostaje BLOCKED bez osobnej zgody użytkownika.
- Nie zmieniono `versionName=0.6.54` ani `versionCode=80`.

## Następny zakres audytu
1. Przejrzeć wszystkie miejsca tworzące `beginRequestScope()` i ocenić, czy globalne unieważnianie starszych zakresów jest prawidłowe przy równoległych testach modeli, czatach i zadaniach w tle.
2. Dodać/zweryfikować regresje dla timeoutu agenta w `TeamRunEngine`, w tym brak fallbacku po anulowaniu.
3. Przejrzeć `ApiTokenTracker.planBatch/releaseBatchReservation/recordQuery` pod kątem wszystkich ścieżek wyjątków i rezerwacji.
4. Przygotować wiarygodną ścieżkę uruchamiania testów (CI/Gradle wrapper) dopiero po sprawdzeniu wersji JDK, AGP, Gradle i SDK; nie deklarować PASS bez uruchomienia.

## Uzupełnienie — ścieżka MASTER i rezerwacja syntezy (2026-11-06)

Dalszy przegląd ujawnił, że ścieżka `MainActivity.chatWithFallback()` miała podobne ryzyka jak wcześniej TeamRunEngine:
- lokalna odmowa schedulera zapisywała fałszywą porażkę zdrowia modelu;
- błędy lokalnego zapisu kondycji, historii użycia lub tokenów mogły wejść do catch dostawcy po poprawnej odpowiedzi i uruchomić fallback;
- końcowa synteza MASTER-a korzystała z kontroli limitu, ale nie miała własnej aktywnej rezerwacji budżetu obejmującej faktyczne wywołanie.

Wprowadzone poprawki:
1. Usunięto negatywny zapis zdrowia providera dla lokalnej konkurencji/cooldownu schedulera.
2. Oddzielono zapis kondycji, historii użycia i tokenów od wyniku żądania API. Błędy lokalne są logowane; nie mogą uruchamiać drugiego wywołania po udanej odpowiedzi.
3. Po odebraniu odpowiedzi i rozliczeniu wykorzystania anulowane zadanie nie publikuje wyniku.
4. Końcowa synteza MASTER-a otrzymuje własny `planBatch` z rezerwacją tokenów i kosztu. Rezerwacja jest przekazywana do `recordQuery` i zwalniana w `finally`.
5. Dodano test `synthesisReservationIsConsumedByActualMasterUsageAndReleased` w `ApiTokenTrackerTest.kt`.

Dodatkowe pliki zmienione:
- `app/src/main/java/pl/aimasterpro/app/MainActivity.kt`
- `app/src/test/java/pl/aimasterpro/app/core/ApiTokenTrackerTest.kt`

Weryfikacja pozostaje NOT RUN: nie wykonano kompilacji Android ani JUnit/Robolectric. Powyższe zmiany wymagają uruchomienia testów przed uznaniem ich za poprawne.

## Uzupełnienie — izolacja skutków ubocznych po odpowiedzi (2026-11-06)

Dalsza kontrola wykazała, że nie tylko zapis tokenów, ale również callbacki logowania i telemetrii po udanej odpowiedzi mogły wejść do bloku obsługi błędów providera. W konsekwencji lokalny błąd mógł uruchomić fallback mimo odebrania poprawnej odpowiedzi.

Wprowadzone poprawki:
- `TeamRunEngine`: po udanym HTTP zapis kondycji, scheduler, zapis użycia oraz logi COMPLETE/FAILOVER/REPORT są odizolowane od ścieżki błędu dostawcy. Błąd telemetrii nie powinien powodować ponownego wywołania modelu.
- `TeamRunEngine`: zapis nieudanej kondycji modelu jest odporny na wyjątek lokalnego callbacku.
- `MainActivity.chatWithFallback()`: ten sam wzorzec izolacji obejmuje zapis kondycji, cooldownu, historii użycia i tokenów; odmowa lease przez lokalny scheduler nie obniża kondycji providera.
- Końcowa synteza MASTER-a ma własną rezerwację budżetu utworzoną po rozliczeniu agentów i recenzji. Rezerwacja jest przekazywana do zapisu faktycznego użycia i zwalniana w `finally`.
- Dodano test regresyjny, w którym lokalne logowanie COMPLETE/REPORT rzuca wyjątek, a zadanie ma nadal pozostać COMPLETED bez ponownego HTTP.

Dodatkowe testy dodane w tym etapie:
- `ProviderClientNetworkTest.cancellingOneChildScopeDisconnectsOnlyThatAgentRequest`
- `TeamRunEngineNetworkTest.postResponseLoggingFailureDoesNotRetrySuccessfulAgentRequest`
- `ApiTokenTrackerTest.synthesisReservationIsConsumedByActualMasterUsageAndReleased`

Wszystkie pozostają NOT RUN do czasu uruchomienia JUnit/Robolectric i kompilacji Android.

## Uzupełnienie — izolacja równoległych sesji i anulowanie nadrzędne (2026-11-06)

Weryfikacja call-site'ów `beginRequestScope()` wykazała istotny problem architektoniczny: tworzenie nowego zakresu zwiększało globalny epoch i rozłączało starsze żądania. Oznaczało to, że uruchomienie testu modelu, audytu jakości lub innej niezależnej operacji mogło przerwać trwający czat albo pracę innego agenta.

Poprawki:
1. `beginRequestScope()` tworzy teraz niezależny zakres w aktualnym epoch. Samo rozpoczęcie nowej operacji nie anuluje innych. Globalne unieważnienie pozostaje wyłącznie w jawnej operacji `cancelActiveRequests()`.
2. Zakresy potomne przechowują referencję do rodzica. Anulowanie nadrzędnego zakresu jest propagowane do wszystkich potomnych połączeń i żądań.
3. `MainActivity.stopGeneration()` anuluje teraz wyłącznie zakres aktualnego czatu, zamiast globalnie rozłączać wszystkie testy modeli i inne operacje. Czyści też referencję do anulowanego zakresu.
4. Anulowanie pojedynczego agenta nadal działa niezależnie od rodzeństwa w tym samym batchu.

Dodane testy:
- `ProviderClientNetworkTest.startingIndependentTopLevelScopesDoesNotCancelEarlierRequest`
- `ProviderClientNetworkTest.cancellingParentScopeDisconnectsItsAgentChildrenOnly`

Zidentyfikowany problem potwierdzono przez analizę wszystkich widocznych wywołań `beginRequestScope()` w `MainActivity`. Poprawka została zapisana na gałęzi roboczej; testy nadal NOT RUN.

## Uzupełnienie — prywatność checkpointów (2026-11-06)

Przegląd zapisu błędów wykazał, że `TeamRunEngine` przekazywał pełny tekst wyjątku do `TaskCheckpointStore`. Treść wyjątku dostawcy może zawierać dane zwrócone przez serwer, dlatego nie powinna trafiać do trwałego dziennika checkpointów.

Poprawka:
- szczegółowy błąd nadal może trafić do bieżącego logu diagnostycznego;
- checkpoint przechowuje wyłącznie typ wyjątku oraz kategorię błędu providera i opcjonalny kod HTTP;
- nie zapisuje dowolnej treści komunikatu wyjątku do metadanych odzyskiwania.

Dodatkowy przegląd potwierdził, że ID zadań zespołu są budowane z identyfikatora czatu, żądania, indeksu i roli agenta, co ogranicza kolizje pomiędzy równoległymi czatami.

## Uzupełnienie — przerwanie koordynatora (2026-11-06)

Dodatkowo zabezpieczono przypadek, w którym przerwany zostaje wątek koordynatora odbierający wyniki z `Future`:
- przy `InterruptedException` silnik anuluje zakres konkretnego agenta, wywołuje `future.cancel(true)` i przywraca flagę przerwania wątku;
- dzięki temu anulowanie nie kończy się wyłącznie na przerwaniu oczekiwania na wynik — rozłączane jest również aktywne żądanie HTTP tego agenta;
- pozostałe zakresy są anulowane przy dalszym przetwarzaniu przerwanego batcha, a nadrzędny zakres czatu propaguje anulowanie do dzieci.

Kod gałęzi roboczej zweryfikowany po tych zmianach: `dac290b1f3684778c9a46b6bd5c7f782beb8eccb`.
`main` nadal pozostaje na `752149d40186144b0f458a32b5826c8928349c3a`.


## Uzupełnienie — trwałość checkpointów i rozliczeń API (2026-11-06)

W kolejnym etapie sprawdzono odzyskiwanie zadań po restarcie, zapis dziennika checkpointów, rejestr użycia API oraz identyfikatory zadań.

### Ustalenia

1. MainActivity.onCreate() wywołuje TaskCheckpointStore.recoverStaleRunning() przed rozpoczęciem normalnej pracy interfejsu.
2. Odzyskiwanie obejmuje zarówno ostatni stan QUEUED, jak i RUNNING pochodzący z innego procesu. Są one oznaczane INTERRUPTED; nie są automatycznie wysyłane ponownie.
3. Identyfikatory zadań zespołu w głównym czacie zawierają identyfikator czatu, UUID żądania, indeks i rolę agenta. Żądanie czatu i synteza MASTER mają osobne identyfikatory. Nie wykryto kolizji w sprawdzonych ścieżkach.
4. Rezerwacje budżetu w ApiTokenTracker są przechowywane w pamięci procesu. Po śmierci procesu znikają, ale w badanej ścieżce aplikacja nie wznawia automatycznie przerwanych zadań. Trwała historia wykorzystania API pozostaje osobnym rejestrem.
5. Wcześniej ignorowano wynik SharedPreferences.commit() dla checkpointów, a rejestr tokenów używał apply(), które nie potwierdza zakończenia zapisu na dysku przed powrotem z funkcji.

### Poprawki wprowadzone

1. TaskCheckpointStore.record() zwraca teraz Boolean i używa synchronicznego commit(). Błąd zapisu nie jest już przedstawiany jako potwierdzony checkpoint.
2. recoverStaleRunning() zlicza wyłącznie przerwane zadania, których nowy stan INTERRUPTED został trwale zapisany; nieudany zapis trafia do Android Log.
3. Wszystkie trzy sprawdzone callbacki onTaskState w MainActivity raportują niepowodzenie trwałego zapisu checkpointu.
4. TaskCheckpointStore.latest() wybiera najnowszy stan na podstawie kolejności dziennika (najnowsze zdarzenie jest wstawiane na początek), a nie wyłącznie znacznika czasu. Zapobiega to wybraniu starego stanu w przypadku identycznych znaczników czasu.
5. ApiTokenTracker.saveRecordToStorage() używa teraz commit() i zgłasza błąd, jeśli zapis trwały nie zostanie potwierdzony. W ścieżce TeamRunEngine błąd zapisu usage jest przechwytywany jako błąd księgowania po odpowiedzi i nie może uruchomić ponownego płatnego żądania.
6. Dodano testy latestUsesJournalOrderWhenTimestampsCollide i recordReportsSuccessfulDurableWrite.

### Pliki zmienione w tym etapie

- app/src/main/java/pl/aimasterpro/app/core/TaskCheckpointStore.kt
- app/src/test/java/pl/aimasterpro/app/core/TaskCheckpointStoreTest.kt
- app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt
- app/src/main/java/pl/aimasterpro/app/MainActivity.kt

### Ograniczenia i weryfikacja

- Testy nadal NOT RUN — nie uruchomiono Gradle/JUnit/Robolectric ani kompilacji Android.
- Nie wykonano testu rzeczywistej śmierci procesu Android ani wymuszonego błędu zapisu SharedPreferences.
- Rezerwacje budżetu nadal są pamięciowe; ich odtworzenie po restarcie nie jest potrzebne, dopóki aplikacja nie wznawia automatycznie przerwanych żądań. Nie należy dodawać takiego automatycznego wznowienia bez osobnego mechanizmu idempotencji.
- Nie zmieniono main, APK, wersji aplikacji ani Companion Transport.


### Dodatkowa regresja — stabilność terminalnego statusu zadania (2026-11-06)

Przegląd ścieżki timeoutu w TeamRunEngine ujawnił wyścig: koordynator mógł najpierw zapisać TIMED_OUT, po czym przerwany worker zapisywał CANCELLED i nadpisywał informację o przyczynie zakończenia. TaskCheckpointStore odrzuca teraz późniejsze przejścia po zapisanym stanie terminalnym (COMPLETED, FAILED, BLOCKED, TIMED_OUT, CANCELLED, INTERRUPTED). Ponieważ wpis terminalny jest już trwale zapisany, zignorowanie spóźnionego callbacku jest traktowane jako poprawny no-op. Dodano test lateWorkerCancellationDoesNotOverwriteCoordinatorTimeout. Test pozostaje NOT RUN.


### Dodatkowe zabezpieczenie — awaria zapisu usage (2026-11-06)

Dalsza kontrola ujawniła, że samo zgłoszenie błędu commit() mogłoby pozostawić wykorzystanie API niewidoczne nawet w bieżącym procesie, mimo że odpowiedź dostawcy już dotarła. Zmieniono więc kolejność księgowania: rekord jest zachowywany w sessionRecords i zużywa odpowiadającą mu aktywną rezerwację również wtedy, gdy zapis na dysku się nie powiedzie. Funkcja zgłasza błąd po zachowaniu rekordu w pamięci, dzięki czemu istniejące callbacki raportują USAGE_RECORD_FAILED, ale nie uruchamiają fallbacku. loadAllRecordsFromStorage() scala historię trwałą z rekordami sesyjnymi po queryId, aby nie dublować poprawnie zapisanych wywołań i nie pomijać tymczasowych wpisów w monitorze/budżecie. Rekord niezapisany na dysku nadal zostanie utracony po śmierci procesu — ta sytuacja jest jawnie raportowana i nie może być przedstawiana jako trwałe rozliczenie. Zmiana w ApiTokenTracker.kt.


## Rozpoczęcie etapu 7 — spójność historii czatów i aktywności agentów (2026-11-06)

Przegląd zapisu historii wykazał, że kilka ścieżek wykonywało operacje read-modify-write na wspólnym dokumencie chats_v2. Większość metod była synchronizowana, ale dopisywanie odpowiedzi do czatu nieaktywnego oraz zapis currentId i zmiana nazwy czatu nie korzystały z tego samego monitora. Równoległy zapis aktywności agenta mógł więc zostać nadpisany przez zapis wiadomości lub odwrotnie.

Poprawki w MainActivity.kt:
- appendToStoredChat() jest teraz synchronizowane z pozostałymi metodami historii;
- saveCurrentId() korzysta z tego samego monitora;
- blok read-modify-write zmiany nazwy czatu jest chroniony przez synchronized(this).

Nie zmieniano jeszcze modelu trwałości historii: zapisy historii czatu nadal używają SharedPreferences.apply(). Nie zastępowano go commit() na wątku UI, aby nie wprowadzać ryzyka blokowania interfejsu. W kolejnym kroku należy sprawdzić kolejność zapisów i lifecycle flush oraz to, czy log aktywności agentów, assistantTaskIds i wiadomości pozostają spójne przy szybkiej zmianie czatu lub zamknięciu procesu.

## Uzupełnienie — ochrona dziennika przed nadpisaniem po uszkodzeniu (2026-11-06)

W kolejnym przeglądzie znaleziono ryzyko utraty historii checkpointów: wcześniejszy odczyt JSON zwracał pustą listę zarówno dla prawidłowo pustego dziennika, jak i dla uszkodzonych danych. Następny zapis mógł więc potraktować uszkodzony dziennik jako pusty i zastąpić go nowym wpisem, bez zachowania istniejącej zawartości.

Poprawka:
- dodano odrębny, ścisły odczyt dziennika (readEvents), który rozróżnia poprawną pustą historię od błędu parsowania lub nieprawidłowego wiersza;
- record() odmawia dopisania zdarzenia, gdy istniejący dziennik jest uszkodzony; nie wykonuje zapisu zastępczego i nie nadpisuje surowych danych;
- publiczny load() nadal zwraca pustą listę przy błędzie odczytu dla bezpiecznej obsługi ekranów, ale ścieżka zapisu nie interpretuje tego wyniku jako zgody na nadpisanie.

Dodane testy regresyjne:
- corruptJournalIsNotSilentlyReplacedByAnEmptyHistory
- malformedJournalRowAlsoBlocksAppendInsteadOfDroppingUnknownData

Pliki zmienione:
- app/src/main/java/pl/aimasterpro/app/core/TaskCheckpointStore.kt
- app/src/test/java/pl/aimasterpro/app/core/TaskCheckpointStoreTest.kt

Weryfikacja: zmiany i testy zapisano na branchu roboczym. Testy pozostają NOT RUN — nadal nie potwierdzono uruchomienia Gradle/JUnit/Robolectric ani kompilacji Android. W przypadku uszkodzonego dziennika nowe wpisy będą blokowane do czasu diagnostyki/naprawy danych; to celowe zachowanie fail-safe, a nie automatyczne czyszczenie historii.

Stan referencyjny przed zapisem tego uzupełnienia:
- HEAD branch: 4acdf6f1ac597403954cfec64d61c5e138ef3654
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a

## Uzupełnienie — terminalny status dla zadań oczekujących po przerwaniu koordynatora (2026-11-06)

Dalsza analiza TeamRunEngine wykazała wyścig w ścieżce InterruptedException koordynatora. Future zadania mogło zostać wysłane do puli, ale worker nie musiał jeszcze rozpocząć wykonywania. W takim przypadku nie uruchamiał się workerowy catch, który zapisuje CANCELLED, a checkpoint pozostawał w stanie QUEUED do następnego uruchomienia aplikacji.

Poprawka:
- w obsłudze InterruptedException koordynator nadal anuluje zakres HTTP konkretnego agenta i Future;
- dodatkowo zapisuje terminalny stan CANCELLED dla zadania, za które aktualnie odbierał wynik;
- spóźniony callback workera nie nadpisze już tego terminalnego statusu dzięki ochronie w TaskCheckpointStore;
- kolejne Future w przerwanym batchu również przechodzą przez obsługę InterruptedException i dostają terminalny checkpoint, zamiast pozostawać QUEUED.

Dodano test regresyjny:
- coordinatorInterruptionMarksQueuedTasksCancelled — uruchamia batch z jednym workerem, czeka na pierwsze żądanie HTTP, przerywa koordynator i sprawdza terminalny status zarówno zadania aktywnego, jak i oczekującego.

Pliki zmienione:
- app/src/main/java/pl/aimasterpro/app/agents/TeamRunEngine.kt
- app/src/test/java/pl/aimasterpro/app/agents/TeamRunEngineNetworkTest.kt

Test pozostaje NOT RUN. Nie wykonano kompilacji ani JUnit/Robolectric; test wymaga potwierdzenia w środowisku Gradle/Android.

Stan referencyjny przed zapisem tego uzupełnienia:
- HEAD branch: 949cd5d1cceefa1cff2a8dbf2fc07966f888ea0d
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a

## Uzupełnienie — fail-safe dla uszkodzonego rejestru użycia API (2026-11-06)

Audyt księgowania API ujawnił analogiczny, lecz bardziej krytyczny problem niż wcześniej w checkpointach: loadAllRecordsFromStorage() zwracało pustą historię, jeśli JSON rejestru użycia był uszkodzony. saveRecordToStorage() korzystało z tego wyniku i mogło zastąpić poprzedni rejestr nowym wpisem. Ponadto planBatch() i checkLimits() mogły policzyć budżet bez brakujących, potencjalnie płatnych rekordów.

Poprawki w ApiTokenTracker.kt:
- dodano ścisły parser readPersistedRecords(), który zwraca null przy błędzie parsowania lub nieprawidłowym rekordzie, odróżniając uszkodzenie od prawidłowo pustego rejestru;
- saveRecordToStorage() odmawia zapisu zastępczego, gdy istniejący rejestr jest nieczytelny. recordQuery() nadal zachowuje świeży rekord w sessionRecords, zużywa dopasowaną rezerwację i zgłasza błąd trwałego zapisu, dzięki czemu wywołujący raportuje lukę księgową bez ponowienia płatnego żądania;
- planBatch() wstrzymuje wszystkie nowe zadania, jeśli rejestr jest uszkodzony lub nieczytelny, zamiast zaniżać wykorzystanie budżetu;
- checkLimits() również blokuje zapytanie przy nieczytelnym rejestrze;
- BudgetUsageSnapshot otrzymał ledgerHealthy, a przy niezdrowym rejestrze pozostały budżet jest prezentowany jako 0, nie jako pozornie dostępny limit;
- planBatch() odrzuca niepuste, ale powtarzające się taskId, zanim utworzy rezerwację.

Dodane testy w ApiTokenTrackerTest.kt:
- corruptUsageLedgerIsNotOverwrittenByNewUsageRecord
- corruptUsageLedgerBlocksNewBudgetReservations
- duplicateBudgetTaskIdsAreRejectedBeforeReservation
- corruptUsageLedgerBlocksLegacyLimitCheckAndReportsUnknownBudget

Ograniczenia:
- loadAllRecordsFromStorage() nadal może zwrócić dane sesyjne do bieżącego monitora, ale nieczytelny trwały rejestr jest sygnalizowany przez ledgerHealthy=false; ścieżki autoryzujące nowe żądania używają ścisłego odczytu i fail closed;
- rekord po nieudanym commit() istnieje tylko w pamięci procesu. Jeśli proces zostanie zabity przed naprawą rejestru i kolejnym trwałym zapisem, wpis sesyjny może zniknąć. Stan ten jest jawnie zgłaszany jako USAGE_RECORD_FAILED; nie wolno przedstawiać go jako trwale rozliczonego;
- testy nadal NOT RUN. Nie uruchomiono Gradle/JUnit/Robolectric ani kompilacji Android.

Pliki zmienione:
- app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt
- app/src/test/java/pl/aimasterpro/app/core/ApiTokenTrackerTest.kt

Stan referencyjny przed zapisem tego uzupełnienia:
- HEAD branch: ba2f9fbe21b91187cecf461cf28e2dbcd185e002
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a

## Uzupełnienie — potwierdzanie trwałego czyszczenia rejestru API (2026-11-06)

Dodatkowy przegląd operacji czyszczenia wykazał, że clearRecords() używało SharedPreferences.apply(), a interfejs natychmiast pokazywał sukces i usuwał również pozostałe metryki. apply() nie potwierdza trwałego zapisu przed powrotem.

Poprawki:
- ApiTokenTracker.clearRecords() zwraca Boolean i używa commit(); pamięć sesyjna oraz aktywne rezerwacje są czyszczone wyłącznie po potwierdzonym usunięciu trwałego rejestru;
- MainActivity wykonuje synchroniczny commit poza wątkiem UI, a wynik pokazuje dopiero po zakończeniu;
- jeśli usunięcie rejestru użycia API nie powiedzie się, pozostałe metryki nie są czyszczone, a interfejs nie ogłasza sukcesu;
- test clearRecordsRemovesPersistedUsageOnlyAfterDurableCommit sprawdza wynik i stan SharedPreferences.

Test pozostaje NOT RUN. Nie przeprowadzono testu wymuszonego błędu commit() ani kompilacji Android.

Pliki zmienione:
- app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt
- app/src/main/java/pl/aimasterpro/app/MainActivity.kt
- app/src/test/java/pl/aimasterpro/app/core/ApiTokenTrackerTest.kt

Stan referencyjny przed zapisem tego uzupełnienia:
- HEAD branch: b1e8f65e0939fa31ff74d88deefe9686dd52403a
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


## Uzupełnienie — spójność rezerwacji budżetowych i walidacja kosztów (2026-11-06)

### Ustalenia audytu

1. Rezerwacja zadania była konsumowana po samym `taskId` i `agentId`. Przy fallbacku mogło to zmniejszyć rezerwację wycenioną dla innego providera/modelu.
2. Suma tokenów była liczona zwykłym dodawaniem `Long`. Skrajnie duże, lecz nieujemne wartości mogły przepełnić licznik i zawinąć się do wartości ujemnej, zaniżając wykorzystanie limitu.
3. Ścieżka księgowania nie odrzucała jawnie ujemnych liczników, przepełnienia `inputTokens + outputTokens`, nieprawidłowych kosztów ani niepoprawnego kursu walutowego.
4. Ścisły odczyt trwałego rejestru sprawdzał wcześniej liczniki podstawowe, ale nie wszystkie pola kosztowe oraz liczniki reasoning/cache.
5. Konfiguracja limitów zapisana w SharedPreferences mogła zawierać niefinitywny/ujemny miesięczny limit; ścieżki planowania i starszy `checkLimits()` muszą blokować działanie przy uszkodzonej konfiguracji.
6. Rezerwacja globalna jest celowym buforem tokenów dla późniejszego etapu (np. syntezy MASTER-a), a nie rezerwacją przypisaną do konkretnego providera. Jest doliczana do globalnego limitu dziennego i pozostaje aktywna do jawnego zwolnienia. Końcowa synteza MASTER-a w głównej ścieżce dostaje osobną rezerwację po rozliczeniu agentów i recenzji; wcześniejsze rezerwacje etapów są zwalniane w `finally`.

### Poprawki wprowadzone

W `ApiTokenTracker.kt`:

- aktywna rezerwacja zadania przechowuje teraz `taskId`, `agentId`, `providerId`, `baseUrl` i `modelName`;
- faktyczne użycie konsumuje rezerwację tylko przy pełnej zgodności zadania, agenta, providera, adresu bazowego i modelu. Użycie z fallbacku innego providera/modelu nadal jest księgowane jako faktyczne użycie, ale nie zwalnia wyceny przypisanej do pierwotnego profilu. Pozostała rezerwacja jest zachowawczo utrzymywana do zwolnienia batcha;
- rzeczywiste liczniki tokenów muszą być nieujemne, a ich suma jest sprawdzana przez `Math.addExact`;
- wycena, kurs USD/PLN i wyliczone koszty muszą być skończone i nieujemne; kurs można ustawić tylko w zakresie 0,01–1000;
- ścisły parser rejestru odrzuca ujemne liczniki oraz ujemne/niefinitywne koszty;
- `planBatch()` odrzuca puste identyfikatory providera, ujemne estymacje, przepełnienie sumy estymacji, ujemną rezerwę globalną oraz nieprawidłowe zapisane limity;
- `setLimits()` odrzuca niefinitywny lub ujemny limit kosztów; starsze `checkLimits()` fail-closed przy nieprawidłowej konfiguracji i ujemnej estymacji;
- sumowanie wykorzystania tokenów i aktywnych rezerwacji używa nasycającej arytmetyki, która nie pozwala przepełnić `Long` i zaniżyć wykorzystania budżetu;
- migawka budżetu wyznacza `ledgerHealthy` na podstawie ścisłego odczytu rejestru.

### Testy regresyjne dodane

W `ApiTokenTrackerTest.kt`:

- `reservationIsConsumedOnlyByTheProviderThatWasBudgeted` — fallback innego providera oraz innego modelu nie konsumuje rezerwacji pierwotnego profilu; dokładnie dopasowany profil ją konsumuje;
- `actualUsageAboveEstimateIsStillCountedAgainstTheNextBatch` — rzeczywiste użycie powyżej estymacji ogranicza następny batch;
- `negativeUsageAndTokenOverflowAreRejected` — odrzucenie ujemnych tokenów i przepełnienia sumy;
- `invalidExchangeRatesAreRejected` — odrzucenie NaN, zera i nieskończoności;
- `negativePersistedCostMakesUsageLedgerUnhealthy` — uszkodzony koszt w trwałym rejestrze blokuje nową rezerwację.

### Status i ograniczenia

- Zmiany zapisane wyłącznie na branchu roboczym.
- Testy: **NOT RUN** — nie potwierdzono środowiska Gradle/Android/JUnit/Robolectric; nie deklarować PASS.
- Nie wykonano kompilacji APK.
- Nie zmieniono `main`, numeru wersji aplikacji ani Companion Transport.
- Bufor `globalReserveTokens` pozostaje globalnym buforem dziennego limitu i nie jest konsumowany przez wywołania agentów; jest zwalniany wraz z rezerwacją etapu. To zachowanie jest zamierzone i udokumentowane, a końcowa synteza ma osobną rezerwację zadania.

Pliki zmienione w tym etapie:
- `app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt`
- `app/src/test/java/pl/aimasterpro/app/core/ApiTokenTrackerTest.kt`

Stan po zapisaniu kodu, przed aktualizacją tego checkpointu:
- HEAD branch: 7e7b44fe5f8e9dba5cf95ab0f845c468f864b26d
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


### Dodatkowa kontrola — współdzielony cennik i cykl życia rezerwacji

Po podstawowych poprawkach sprawdzono wszystkie widoczne wywołania `planBatch()` i `releaseBatchReservation()` w `MainActivity.kt`:

- główny batch agentów zwalnia rezerwację w `finally`;
- etap Peer Review zwalnia rezerwację zarówno przy braku dopuszczonych recenzji, jak i po uruchomieniu recenzji w `finally`;
- końcowa synteza MASTER-a zwalnia własną rezerwację w `finally`, a odmowę budżetu obsługuje przed wysłaniem żądania;
- niezależny Patch Peer Review zwalnia rezerwację przy zablokowaniu pełnego składu oraz w `finally` po uruchomieniu;
- nie znaleziono w tych czterech ścieżkach normalnego wyjścia bez jawnego zwolnienia rezerwacji.

Dodatkowo wykryto, że `getPricing()` zwracało bezpośrednią referencję do mutowalnego obiektu z prywatnej listy cen domyślnych. Zewnętrzny caller mógł zmienić stawkę na NaN lub wartość ujemną i wpłynąć na kolejne obliczenia. Funkcja zwraca teraz kopię obiektu cennika, a test `mutatingReturnedPricingDoesNotChangeSharedDefaults` chroni ten przypadek.

Rozszerzono `reservationIsConsumedOnlyByTheProviderThatWasBudgeted` o przypadki różnego modelu i różnego adresu bazowego. Rezerwację konsumuje dopiero dokładnie zgodny provider + model + baseUrl.

Status testów bez zmian: **NOT RUN**. Kontrola cyklu życia rezerwacji była statycznym przeglądem widocznych call-site'ów; nie jest testem runtime ani potwierdzeniem zachowania przy śmierci procesu.


## Uzupełnienie — wyścigi równoległych batchy i opóźnione zwalnianie rezerwacji (2026-11-06)

### Ustalenia audytu

1. Metody księgowania i planowania były już synchronizowane na poziomie obiektu `ApiTokenTracker`, ale odczyt/zapis cennika, kursu USD/PLN i konfiguracji limitów nie używał tego samego monitora. Konkurencyjna zmiana ustawień mogła wejść w trakcie odczytu parametrów planowania.
2. `setLimits()` przechowywało miesięczny limit w `Float`. Wcześniejsza walidacja sprawdzała skończoność wejściowego `Double`, ale nie skończoność wartości po konwersji. Bardzo duży, choć skończony `Double` mógł zostać zapisany jako `Float.POSITIVE_INFINITY`.
3. Ważniejszy wyścig występował na granicy `TeamRunEngine` / `MainActivity`: `Future.cancel(true)` może oznaczyć Future jako anulowany, zanim kod workera rzeczywiście zakończy wykonywanie. `runBatch()` wywoływał `shutdownNow()` bez oczekiwania na zakończenie wszystkich callable, a `MainActivity` natychmiast zwalniało rezerwację w `finally`. Worker, który jeszcze kończył obsługę odpowiedzi HTTP, mógł więc zaksięgować późne użycie już po zwolnieniu rezerwacji.
4. `clearRecords()` usuwało także aktywne rezerwacje w pamięci. Ręczne czyszczenie historii podczas pracy agentów mogło chwilowo uwolnić budżet, który nadal należał do uruchomionych zadań.

### Poprawki wprowadzone

#### ApiTokenTracker.kt

- `getPricing()`, `getExchangeRate()`, `setExchangeRate()`, `getLimits()` i `setLimits()` korzystają teraz z `@Synchronized`, wspólnego z `planBatch()`, `recordQuery()`, `clearRecords()` i operacjami rezerwacji.
- `setLimits()` sprawdza również wynik konwersji miesięcznego limitu do `Float`; wartość, która przepełniłaby format przechowywania, jest odrzucana.
- Dodano stan zakończenia do każdej rezerwacji zadania oraz znacznik żądania zwolnienia całego batcha.
- `requestBatchReservationRelease()` nie usuwa rezerwacji, dopóki wszystkie przypisane do niej workery nie zgłoszą rzeczywistego zakończenia.
- `completeReservationTask()` oznacza zakończenie pojedynczego workera i usuwa rezerwację dopiero wtedy, gdy release został zażądany i wszystkie zadania są zakończone.
- Dotychczasowe `releaseBatchReservation()` pozostaje natychmiastowym zwolnieniem dla ścieżek, w których zadania nie zostały wysłane (np. wszystkie zostały zablokowane przez budżet).
- `clearRecords()` usuwa historię trwałą i sesyjną, ale **nie usuwa aktywnych rezerwacji**. Nie może więc uwolnić budżetu nadal używanego przez workery.
- Dodano wyłącznie testowy, wewnętrzny `resetInMemoryStateForTests()`, aby izolować stan singletonu między testami bez udostępniania aplikacji funkcji czyszczenia aktywnych rezerwacji.

#### TeamRunEngine.kt

- Dodano callback `onTaskFinished(taskId)`, wywoływany z `finally` callable workera — po zakończeniu obsługi odpowiedzi/usage, także przy błędzie i przerwaniu.
- Każde zadanie ma atomowy stan cyklu życia: queued (0), running (1), finished/cancelled-before-start (2).
- Przy timeout lub przerwaniu koordynator anuluje Future i oznacza jako zakończone tylko zadanie, które nie zdążyło wystartować. Jeśli worker już działa, rezerwacja pozostaje aktywna aż do jego własnego `finally`.
- Końcowy `finally` obsługuje również wyjątek przed wysłaniem wszystkich zadań: nieuruchomione zadania dostają callback zakończenia, a aktywne zakresy HTTP są anulowane przed `shutdownNow()`.
- Walidacja zadań została przeniesiona do chronionego bloku `try/finally`, aby nawet błąd przed submission nie pozostawiał rezerwacji bez callbacku zakończenia.

#### MainActivity.kt

- Główny batch agentów, Peer Review i niezależny Patch Peer Review przekazują `onTaskFinished` do `ApiTokenTracker.completeReservationTask()`.
- Ich bloki `finally` wywołują teraz `requestBatchReservationRelease()`, a nie natychmiastowe usunięcie rezerwacji.
- Ścieżki bez wysłanych workerów oraz synchroniczna synteza MASTER-a nadal używają natychmiastowego zwolnienia, ponieważ nie mają aktywnego callable, które mogłoby później zaksięgować użycie.

### Testy regresyjne

W `ApiTokenTrackerTest.kt` dodano:

- `deferredReservationReleaseWaitsForLateWorkerUsageAndCompletion` — rezerwacja pozostaje aktywna po żądaniu zwolnienia, przyjmuje późne usage i znika dopiero po callbacku zakończenia;
- `clearingUsageHistoryDoesNotFreeInFlightBudgetReservations` — czyszczenie historii nie uwalnia budżetu aktywnego zadania;
- `concurrentBatchPlanningCannotReserveTheSameDailyBudgetTwice` — dwa równoległe planowania nie rezerwują podwójnie tych samych tokenów;
- `concurrentUsageWritesAreSerializedAndBothRecordsPersist` — dwa równoległe zapisy usage zachowują oba rekordy;
- `monthlyLimitThatOverflowsFloatStorageIsRejected` — konwersja do Float nie może zapisać nieskończonego limitu.

W `TeamRunEngineNetworkTest.kt`:

- rozszerzono test awarii lokalnego księgowania, aby potwierdzić kolejność callbacków usage → zakończenie workera;
- `preSubmitValidationFailureStillSignalsTaskCompletion` — błąd walidacji przed submission nie pozostawia rezerwacji bez zakończenia;
- rozszerzono `coordinatorInterruptionMarksQueuedTasksCancelled`, aby sprawdzić callback zakończenia zarówno dla workera aktywnego, jak i oczekującego.

Liczba testów w aktualnych plikach:
- `ApiTokenTrackerTest.kt`: 19 testów;
- `TeamRunEngineNetworkTest.kt`: 7 testów.

### Zakres ochrony i ograniczenia

- W `AndroidManifest.xml` nie znaleziono jawnych deklaracji `android:process`; obecne komponenty aplikacji działają w domyślnym procesie. `@Synchronized` chroni więc konkurencję wewnątrz bieżącego procesu aplikacji.
- To nie jest blokada międzyprocesowa. Gdyby w przyszłości komponenty budżetowe zostały uruchomione w osobnych procesach, SharedPreferences + monitor JVM nie zapewnią transakcyjnego read-modify-write; wtedy potrzebna będzie baza z transakcjami (np. SQLite/Room) lub inny mechanizm międzyprocesowy.
- Jeśli worker ignoruje przerwanie i nie kończy się, jego rezerwacja pozostaje zajęta do rzeczywistego zakończenia lub śmierci procesu. Jest to celowe zachowanie fail-safe: lepiej tymczasowo zablokować część limitu niż uwolnić budżet, który może jeszcze zostać zużyty.
- Po śmierci procesu rezerwacje in-memory znikają. Zgodnie z wcześniejszym audytem zadania QUEUED/RUNNING są odzyskiwane jako INTERRUPTED i nie są automatycznie odtwarzane; trwały rejestr usage pozostaje osobny.
- Testy: **NOT RUN**. Nie uruchomiono Gradle/JUnit/Robolectric ani kompilacji Android. Nie deklarować PASS.

### Pliki zmienione w tym etapie

- `app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt`
- `app/src/test/java/pl/aimasterpro/app/core/ApiTokenTrackerTest.kt`
- `app/src/main/java/pl/aimasterpro/app/agents/TeamRunEngine.kt`
- `app/src/test/java/pl/aimasterpro/app/agents/TeamRunEngineNetworkTest.kt`
- `app/src/main/java/pl/aimasterpro/app/MainActivity.kt`

Stan referencyjny po zapisaniu kodu, przed aktualizacją tego checkpointu:
- HEAD branch: 4f270eb2efd38929da0df4ffc30bbc37565d8cdd
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


### Dodatkowe testy wyścigów po przeglądzie końcowym

Doprecyzowano test czyszczenia historii podczas aktywnego workera: po wyczyszczeniu historii test wykonuje jeszcze późny zapis usage przez zachowaną rezerwację, sprawdza trwały rekord i zmniejszenie rezerwy, a dopiero potem kończy worker i zwalnia batch.

Dodano również:
- `concurrentReservationReleaseAndCompletionAlwaysRetireTheBatch` — dwa wątki równolegle zgłaszają żądanie zwolnienia batcha i zakończenie workera. Niezależnie od kolejności operacji rezerwacja ma zostać usunięta po obu zdarzeniach.

Po rozszerzeniu:
- `ApiTokenTrackerTest.kt`: 20 testów;
- `TeamRunEngineNetworkTest.kt`: 7 testów.

Testy nadal **NOT RUN**. Nie wykonano kompilacji ani uruchomienia JUnit/Robolectric.

Stan referencyjny kodu przed zapisaniem tego uzupełnienia:
- HEAD branch: 5d88924de636ca95f39fba8382096742de9895ea
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


### Dodatkowa poprawka — atomowa rezerwacja dla pojedynczego czatu MASTER-a

Kontrola wszystkich ścieżek ujawniła jeszcze jedno miejsce poza TeamRunEngine: zwykły, pojedynczy czat bez przekazanego `budgetReservationId` używał `checkLimits()`. Ta metoda wykonywała tylko kontrolę odczytową i nie rezerwowała limitu. Dwa równoległe czaty mogły więc jednocześnie przejść kontrolę na podstawie tego samego wolnego budżetu.

Poprawka w `MainActivity.chatWithFallback()`:

- usunięto użycie `checkLimits()` z tej ścieżki;
- przed rozpoczęciem routingu tworzona jest atomowa rezerwacja przez `planBatch()` dla pierwszego dopuszczonego profilu z rankingu;
- rezerwacja obejmuje szacowany input oraz 4096 tokenów output;
- jej identyfikator jest przekazywany do `recordQuery()`, więc dokładnie zgodny provider/model/endpoint konsumuje rezerwację;
- jeżeli routing przejdzie na inny profil, rezerwacja pierwotnego profilu pozostaje zachowawczo aktywna do końca synchronicznej próby; rzeczywiste użycie fallbacku jest księgowane przed zwolnieniem;
- rezerwacja jest zwalniana w `finally` po zakończeniu całej pętli providerów, niezależnie od sukcesu, błędu lub wyjątku;
- log informujący o rezerwacji jest best-effort, aby awaria samego logowania nie pozostawiła rezerwacji bez bloku `finally`;
- estymacja input używa arytmetyki `Long`, aby uniknąć przepełnienia przy dodawaniu długości promptów.

Po tej zmianie `MainActivity` nie ma już wywołań `ApiTokenTracker.checkLimits()` dla pojedynczych czatów; bezpośrednia ścieżka korzysta z tego samego atomowego mechanizmu rezerwacji co batch agentów. Sama metoda `checkLimits()` pozostaje w trackerze jako starsze API/kompatybilność i jest nadal fail-closed, ale nie jest już mechanizmem autoryzacji pojedynczego czatu.

Stan referencyjny kodu przed zapisaniem tego uzupełnienia:
- HEAD branch: 22647865676c836a2c8e9af0850a3315e789592a
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a

Testy pozostają **NOT RUN**.


### Dodatkowe zabezpieczenie — ręczne testy modeli i providerów

Audyt wszystkich wywołań `client.chat()` w `MainActivity.kt` wykazał trzy ręczne ścieżki diagnostyczne, które wcześniej wysyłały żądanie bez wcześniejszej rezerwacji budżetu:
- test pojedynczego modelu;
- test połączenia providera;
- test połączenia agenta.

Wprowadzono wspólny helper `runBudgetedModelTest()`:

- każda próba otrzymuje unikalny `taskId` i rezerwację przez `planBatch()`;
- wszystkie testy diagnostyczne są grupowane pod agentem budżetowym `TEST`, więc nie rozdzielają limitu per-agent między różne etykiety testów;
- rezerwacja uwzględnia estymowany input i 256 tokenów output oraz koszt modelu/provider-a;
- żądanie HTTP nie jest wysyłane, jeśli rezerwacja została zablokowana;
- rzeczywiste usage jest zapisywane z tym samym `reservationId`;
- rezerwacja jest zwalniana w `finally`;
- zapis kondycji modelu jest best-effort i nie może przerwać księgowania usage po odpowiedzi.

W efekcie jedynym bezpośrednim `client.chat()` poza helperem testowym w `MainActivity` pozostaje ścieżka `chatWithFallback()`, która również ma atomową rezerwację. W tym pliku nie ma już wywołań `ApiTokenTracker.checkLimits()` jako bramki przed pojedynczym żądaniem.

Dodano test `monthlyCostBudgetIncludesActiveReservationsAndRecordedUsage`: potwierdza, że koszt aktywnej rezerwacji blokuje kolejny płatny batch, a po zaksięgowaniu realnego usage koszt nadal blokuje kolejny batch po zwolnieniu rezerwacji.

Aktualna liczba testów:
- `ApiTokenTrackerTest.kt`: 21;
- `TeamRunEngineNetworkTest.kt`: 7.

Testy nadal **NOT RUN** — nie uruchomiono Gradle/JUnit/Robolectric ani kompilacji Android.

Stan referencyjny kodu przed zapisaniem tego uzupełnienia:
- HEAD branch: 38c45669e73dd75d59e509ebaa0a0b430629dfdb
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


### Identyfikatory rezerwacji

Dodatkowo zabezpieczono generowanie identyfikatorów rezerwacji:

- przed utworzeniem wpisu aktywnej rezerwacji generator ponawia UUID, jeśli identyfikator jest pusty albo już występuje w `activeReservations`;
- jeśli plan nie dopuścił żadnego zadania i nie ma globalnego bufora, zwracany `reservationId` jest pusty, ponieważ nie istnieje żadna aktywna rezerwacja do zwolnienia;
- jeśli plan ma dopuszczone zadania lub globalny bufor, otrzymuje niepusty identyfikator i zostaje zapisany w mapie aktywnych rezerwacji.

Test `monthlyCostBudgetIncludesActiveReservationsAndRecordedUsage` sprawdza teraz również niepusty identyfikator aktywnego planu i pusty identyfikator planu całkowicie zablokowanego.

Stan referencyjny kodu przed zapisaniem tego uzupełnienia:
- HEAD branch: 8cb95b734e5277f77b21d627ceac56841b456f3f
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a

Testy nadal **NOT RUN**.


## Uzupełnienie — rezerwa syntezy MASTER-a a limit per-agent (2026-11-06)

Dalszy audyt wykazał, że `globalReserveTokens` chronił dzienny limit globalny, ale nie był przypisywany do limitu agenta. Główna ścieżka batcha rezerwuje tokeny na końcową syntezę MASTER-a; bez powiązania z agentem równoległe lub kolejne zadania MASTER-a mogły wykorzystać tę samą przestrzeń per-agent i pozostawić syntezę bez budżetu.

Poprawki:
- `ApiTokenTracker.planBatch()` przyjmuje opcjonalne `globalReserveAgentId`;
- aktywna rezerwacja przechowuje identyfikator agenta przypisany do bufora globalnego;
- bufor jest doliczany do wykorzystania per-agent przy planowaniu kolejnych zadań oraz przy starszej metodzie `checkLimits()`;
- bufor bieżącego batcha jest uwzględniany przed oceną zadań z tego samego batcha;
- główna ścieżka `MainActivity` przypisuje rezerwę syntezy do `MASTER`.

Dodano testy:
- `globalSynthesisReserveAlsoProtectsMasterPerAgentLimit`;
- `currentGlobalSynthesisReserveIsIncludedBeforePlanningMasterTasks`.

## Uzupełnienie — faza transportu i niejednoznaczny wynik POST (2026-11-06)

W `ProviderClient` faza transportu była wcześniej zakodowana wyłącznie w tekście komunikatu wyjątku. To utrudniało bezpieczne odróżnienie błędu przed wysłaniem żądania od sytuacji, w której POST mógł już dotrzeć do providera.

Poprawki:
- `ProviderException` udostępnia teraz strukturalne pola `phase` i `requestMayHaveBeenProcessed`;
- błędy DNS i połączenia przed wysłaniem są oznaczane jako niebillowalne z perspektywy nieznanego usage;
- timeout/IO po rozpoczęciu wysyłania POST oraz błędy odczytu odpowiedzi oznaczają niejednoznaczny wynik;
- pusta lub niepoprawna odpowiedź HTTP 2xx oraz zbyt duża/nieprawidłowo zakodowana odpowiedź są oznaczane jako możliwe do rozliczenia;
- błędy HTTP z odebranym kodem statusu nie są automatycznie klasyfikowane jako nieznane usage;
- polityka retry pozostaje bez zmian: POST nie jest ponawiany po możliwym przetworzeniu; GET zachowuje dotychczasowe retry.

Testy rozszerzono o asercje fazy i flagi niejednoznacznego usage dla timeoutu odczytu, zbyt dużej odpowiedzi oraz błędu DNS.

### Otwarty punkt audytu

Samo oznaczenie niejednoznacznego POST nie księguje jeszcze estymowanego usage. Następnym krokiem jest bezpieczne połączenie tej informacji z aktywną rezerwacją zadania, tak aby status `UNAVAILABLE` zużywał właściwą rezerwację bez podwójnego księgowania i bez uruchamiania fallbacku. Nie należy szacować kosztu na podstawie dowolnego tekstu wyjątku.

### Status

- Zmiany zapisane wyłącznie na branchu roboczym.
- Testy JUnit/Robolectric i kompilacja Android: **NOT RUN**.
- Nie wykonano APK, bump wersji ani merge.
- Companion Transport pozostaje BLOCKED.
- `main` pozostaje bez zmian.

Stan przed zapisem tego uzupełnienia:
- HEAD branch: 74ca9402c38291be1c76859a432dd736c9dfaa90
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


## Uzupełnienie — księgowanie nieznanego usage po niejednoznacznym POST (2026-11-06)

Dokończono obsługę błędów, w których żądanie POST mogło dotrzeć do providera, ale aplikacja nie odebrała wiarygodnej odpowiedzi.

### Zmiany

- `ProviderException` zawiera fazę transportu oraz flagę `requestMayHaveBeenProcessed`.
- Flaga jest ustawiana dla POST po rozpoczęciu wysyłania, gdy timeout/IO/anulowanie uniemożliwia ustalenie wyniku, oraz przy pustej, niepoprawnej, zbyt dużej lub nieprawidłowo zakodowanej odpowiedzi.
- Błąd DNS/połączenia przed wysłaniem nie jest klasyfikowany jako możliwe rozliczenie.
- Gdy poprawny HTTP 2xx został odebrany, ale treść odpowiedzi nie przechodzi walidacji schematu, również zachowujemy informację o możliwym rozliczeniu.
- `requestWithRetry()` zachowuje znacznik niejednoznacznego wyniku podczas anulowania. POST nie jest ponawiany.
- `TeamRunEngine` zapisuje estymację wejścia oraz przydzielony limit wyjścia jako `UNAVAILABLE` przez callback księgowania; po niejednoznacznym POST zatrzymuje routing dla tego zadania i nie uruchamia fallbacku.
- `MainActivity.chatWithFallback()` zapisuje estymowane usage `UNAVAILABLE` i kończy próbę bez przełączania providera.
- Ręczne testy modelu/providera również zapisują estymację `UNAVAILABLE`, gdy wynik POST jest niejednoznaczny.
- Anulowanie po wysłaniu POST nie gubi już tej informacji: jeśli transport został przerwany w fazie WRITE_REQUEST/WAIT_RESPONSE/READ_RESPONSE, wyjątek CANCELLED zachowuje flagę możliwego rozliczenia. Anulowanie przed wysłaniem pozostaje niebillowalne.

### Testy dodane/rozszerzone

- `ProviderClientNetworkTest`: faza i niejednoznaczne usage po timeout, zbyt dużej odpowiedzi, DNS oraz anulowaniu POST.
- `TeamRunEngineNetworkTest.ambiguousPostOutcomeIsAccountedAsUnavailableAndDoesNotFallback`: timeout odpowiedzi ma uruchomić callback UNAVAILABLE i nie wysłać drugiego POST do fallbacku.
- Testy nie zostały uruchomione; pozostają **NOT RUN**.

### Ograniczenia estymacji

`UNAVAILABLE` oznacza, że rzeczywiste usage providera nie zostało odebrane. Do budżetu zapisujemy konserwatywną estymację wejścia z promptu i wyjścia zarezerwowanego dla danego etapu (lub 4096 tokenów dla pojedynczego czatu, 256 dla testu modelu). To nie jest rzeczywisty licznik dostawcy ani potwierdzenie naliczenia opłaty. W przypadku niezgodnego profilu fallback rezerwacja pierwotnego profilu pozostaje aktywna do zwolnienia batcha, a rekord UNAVAILABLE nadal obciąża globalny i per-agent usage.

### Status

- Zmiany wyłącznie na branchu roboczym.
- Kompilacja Android i JUnit/Robolectric: **NOT RUN**.
- Nie utworzono APK, nie zmieniono wersji i nie wykonano merge.
- Companion Transport pozostaje BLOCKED.

Stan przed zapisem tego uzupełnienia:
- HEAD branch: 7e626942b2d1af233d82cf66e90265643f059dc5
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


## Uzupełnienie — anulowanie po wysłaniu POST i odpowiedzi HTTP 408/5xx (2026-11-06)

Dodatkowa analiza granic transportu wykazała dwa przypadki wymagające rozszerzenia klasyfikacji nieznanego usage:

1. Anulowanie zakresu HTTP po rozpoczęciu wysyłania POST mogło wcześniej zamienić pierwotny błąd transportu na ogólny `CANCELLED`, tracąc informację, że provider mógł już przetworzyć żądanie.
2. `ConnectException` podczas fazy WRITE_REQUEST oraz odpowiedzi HTTP 408/5xx mogły oznaczać częściowe lub pełne przetworzenie żądania, mimo że nie otrzymano poprawnego wyniku modelu.

Poprawki:
- anulowanie w fazie WRITE_REQUEST/WAIT_RESPONSE/READ_RESPONSE zachowuje `requestMayHaveBeenProcessed=true`; anulowanie przed wysłaniem nadal nie jest traktowane jako billable;
- `requestWithRetry()` nie ponawia CONNECT_ERROR, jeśli POST mógł już zostać przetworzony;
- ConnectException po fazie CONNECT jest oznaczany jako niejednoznaczny;
- odpowiedzi HTTP 408 i 5xx dla POST są oznaczane jako niejednoznaczne usage i nie uruchamiają fallbacku;
- odpowiedzi 400/401/403/404 oraz 429 zachowują dotychczasową klasyfikację i obsługę.

Testy:
- rozszerzono test anulowania POST o fazę i flagę nieznanego rozliczenia;
- dodano `postServerErrorIsMarkedAsAmbiguousAndNotReplayed`.

## Uzupełnienie — precyzja miesięcznego limitu kosztów (2026-11-06)

Wykryto przypadek konwersji dodatniego, bardzo małego limitu `Double` do `Float`: wartość mogła zaokrąglić się do `0.0f`, a zero w konfiguracji wyłącza miesięczną bramkę kosztową.

Poprawka:
- `setLimits()` odrzuca dodatni miesięczny limit, który po konwersji do Float stałby się zerem;
- dodano test `positiveMonthlyLimitThatUnderflowsFloatStorageIsRejected`, sprawdzający również, że wcześniejsza wartość limitu nie zostaje nadpisana.

Status weryfikacji pozostaje **NOT RUN**. Nie uruchomiono Gradle, kompilacji Android ani JUnit/Robolectric.

Stan przed zapisem tego uzupełnienia:
- HEAD branch: 685d30fc1fa9066bf15b51f61c3e57db7d01caf7
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


## Uzupełnienie — kompletność księgowania nieznanego usage we wszystkich ścieżkach TeamRunEngine (2026-11-06)

Audyt wszystkich wywołań `TeamRunEngine.runBatch()` w `MainActivity` wykazał, że główny batch agentów miał już callback `onUnknownUsage`, ale dwie pozostałe ścieżki go nie przekazywały:
- Peer Review raportów specjalistów;
- Patch Peer Review.

W tych dwóch ścieżkach niejednoznaczny POST mógł więc zakończyć zadanie bez zapisania estymacji `UNAVAILABLE`, mimo że silnik poprawnie rozpoznawał możliwe przetworzenie żądania. Domyślny callback był pusty.

Poprawki:
- obie ścieżki przekazują teraz `onUnknownUsage` do `ApiTokenTracker.recordQuery()` ze statusem `UNAVAILABLE` i właściwą rezerwacją batcha;
- Peer Review przekazuje estymowany limit wyjścia zgodny z limitem recenzji (maks. 1200 tokenów);
- Patch Peer Review przekazuje limit wyjścia 1200 tokenów zgodny z rezerwacją recenzji;
- rezerwa dzienna Peer Review, która obejmuje końcową syntezę MASTER-a i dodatkowy bufor recenzji, jest teraz również przypisana do limitu per-agent MASTER-a.

Kontrola pozostałych ścieżek:
- główny batch agentów: callback `onUnknownUsage` obecny;
- Peer Review: callback dodany;
- Patch Peer Review: callback dodany;
- pojedynczy czat/fallback: obsługa `UNAVAILABLE` obecna;
- ręczne testy modelu/providera: obsługa `UNAVAILABLE` obecna.

### Dalszy punkt do sprawdzenia

Rezerwa tokenowa `globalReserveTokens` jest teraz uzupełniona o niezależną rezerwę kosztu PLN (`globalReserveCostPln`). Koszt rezerwy jest doliczany do miesięcznego wykorzystania, aktywnych rezerwacji, snapshotu budżetu i starszej kontroli `checkLimits()`. Jeżeli sama rezerwa wraz z dotychczasowym użyciem przekracza limit, `planBatch()` odmawia jej utworzenia (fail closed). Główna ścieżka zespołu rezerwuje koszt syntezy MASTER-a już podczas etapów specjalistów i Peer Review; wycena jest konserwatywna i przyjmuje wyższą stawkę input/output dla całej puli tokenów.

### Status

- Zmiany zapisane wyłącznie na branchu roboczym.
- Testy i kompilacja: **NOT RUN**.
- Nie wykonano APK, bump wersji ani merge.
- Companion Transport pozostaje BLOCKED.

Stan przed zapisem tego uzupełnienia:
- HEAD branch: 443a8fbe4bc28831eeca7855e9b99ff48c0dbd5b
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


## Uzupełnienie — miesięczna rezerwa kosztowa i adekwatność puli syntezy MASTER-a (2026-11-06)

Wykryto, że rezerwa globalna chroniła tokeny, ale nie blokowała równoległym operacjom miesięcznego limitu kosztów PLN. Dodano kosztową część rezerwy:

- `ActiveReservation` przechowuje teraz `globalReserveCostPln`;
- `planBatch()` waliduje rezerwę kosztową (wartość skończona i nieujemna), uwzględnia ją w miesięcznym koszcie bieżącym i odmawia utworzenia rezerwy, jeśli sama przekracza dostępny limit;
- aktywny koszt rezerwy jest uwzględniany w `activeReservationStats()`, `budgetUsageSnapshot()` (przez statystyki) i `checkLimits()`;
- zwolnienie batcha zwalnia również niewykorzystaną rezerwę kosztową;
- główny batch i Peer Review przekazują koszt rezerwy syntezy MASTER-a;
- koszt szacowany jest konserwatywnie: cała pula tokenów syntezy jest wyceniana według wyższej z cen input/output danego modelu i aktualnego kursu USD/PLN;
- pula tokenów syntezy uwzględnia teraz prompt systemowy MASTER-a, instrukcję trybu, raporty, dodatkowy kontekst pamięci/dowodów oraz co najmniej 4096 tokenów wyjściowych (tak jak właściwe wywołanie syntezy);
- rezerwa syntezy jest tworzona także wtedy, gdy wszystkie raporty specjalistów pochodzą z cache i nie ma nowych zadań do wysłania.

Testy dodane w `ApiTokenTrackerTest`:
- `globalSynthesisCostReserveBlocksOtherMonthlySpendingUntilReleased`;
- `globalSynthesisCostReserveAloneCannotExceedMonthlyLimit`.

Testy i kompilacja nadal **NOT RUN**. Kosztowa rezerwa jest etapowa: przed właściwą syntezą końcową wykonywany jest osobny plan tej konkretnej operacji, który ponownie sprawdza bieżący limit i rezerwuje jej rzeczywisty szacunek. Rezerwa etapowa nie jest przedstawiana jako rzeczywisty koszt providera.


### Aktualny stan po uzupełnieniu rezerwy kosztowej

- `ApiTokenTrackerTest.kt`: 26 testów.
- `TeamRunEngineNetworkTest.kt`: 8 testów.
- `ProviderClientNetworkTest.kt`: 14 testów.
- Łącznie w tych trzech plikach: 48 testów; wszystkie **NOT RUN**.
- Główna ścieżka zespołu tworzy rezerwę syntezy również przy pełnym wykorzystaniu cache.
- Końcowa synteza wykonuje własny, świeży `planBatch()`; jeżeli w czasie wcześniejszych etapów limit ulegnie zmianie, synteza zostanie bezpiecznie zablokowana zamiast przekroczyć limit.
- Nie ma potwierdzenia kompilacji ani działania runtime; zmiany wymagają pełnej weryfikacji Gradle/JUnit przed wydaniem.



## Uzupełnienie — odzyskiwanie księgowania po śmierci procesu (2026-11-06)

Audyt trwałości wykazał lukę pomiędzy wysłaniem POST do providera a zapisem rekordu usage. Proces mógł zakończyć się po stronie aplikacji w tym oknie; po restarcie zadanie było oznaczane jako przerwane, ale potencjalny koszt żądania nie trafiał do rejestru budżetu.

### Nowy cykl trwałego checkpointu

- `TeamRunEngine` wywołuje teraz `onRequestStarting` bezpośrednio przed `ProviderClient.chat()`.
- `MainActivity` zapisuje wtedy synchronicznie checkpoint `REQUESTING`, zawierający task/agent/provider/model, estymację input/output, stabilny klucz idempotencji i zredukowany adres endpointu (schemat + host + port; bez ścieżki, query, klucza API ani treści promptu).
- Jeśli zapis checkpointu nie powiedzie się, żądanie HTTP nie jest wysyłane.
- Po trwałym zapisie usage zadanie otrzymuje stan `USAGE_RECORDED`; po zakończeniu `COMPLETED`/inny status końcowy.
- Jeżeli provider odpowiedział, ale lokalny zapis usage nie powiódł się, zadanie pozostaje w stanie `USAGE_PENDING`, a nie terminalnym `COMPLETED`/ `FAILED`.
- Gdy próba została jednoznacznie odrzucona przed możliwym przetworzeniem, checkpoint wraca do `RUNNING` przed próbą fallbacku. Zapobiega to późniejszemu uznaniu poprzedniego, nieprzetworzonego żądania za billable.

### Odzyskiwanie po restarcie

- Przy starcie aplikacji `recoverStaleRunning()` obsługuje teraz `REQUESTING`, `USAGE_PENDING`, `USAGE_RECORDED`, a także wcześniejsze `QUEUED`/`RUNNING`.
- Dla `REQUESTING` i `USAGE_PENDING` aplikacja zapisuje konserwatywne usage ze statusem `UNAVAILABLE`. Jeżeli trwały zapis rejestru usage się nie powiedzie, checkpoint pozostaje `INTERRUPTED_USAGE_PENDING` i jest ponawiany przy kolejnym starcie.
- Dla `USAGE_RECORDED` nie dodaje się drugiego wpisu; zadanie jest jedynie oznaczane jako przerwane, bo usage zostało już utrwalone.
- Zadania nie są automatycznie wznawiane ani ponownie wysyłane do providera. Odzyskiwany jest stan i rozliczenie; raport zadania, którego proces nie dokończył, pozostaje przerwany.

### Idempotentny rejestr usage

- `ApiTokenTracker.recordQuery()` przyjmuje stabilny `idempotencyKey` i używa go jako `queryId`.
- Ponowne księgowanie tego samego task/provider zwraca istniejący rekord zamiast tworzyć duplikat. Jeśli rekord istniał tylko w pamięci sesji po nieudanym commicie, próba odzyskania ponawia jego trwały zapis.
- Ponowne użycie klucza dla innego task/agent/provider/model jest odrzucane.
- Przy odzyskiwaniu istniejącego rekordu nie przeliczamy go według bieżącego cennika ani kursu — zachowujemy oryginalny koszt i status (np. `REAL` zamiast zastąpienia go przez `UNAVAILABLE`).
- `TeamRunEngine` domyślnie zgłasza błąd, jeśli nie ma callbacku trwałego księgowania nieznanego usage; nie może po cichu uznać braku callbacku za poprawne rozliczenie.

### Testy regresyjne dodane/rozszerzone

- `TaskCheckpointStoreTest`: odtworzenie REQUESTING z estymacjami i kluczem idempotencji; retry stanu `INTERRUPTED_USAGE_PENDING`; obsługa `USAGE_RECORDED`; odrzucenie ujemnych estymacji z uszkodzonego dziennika.
- `ApiTokenTrackerTest`: brak podwójnego naliczania po powtórzeniu klucza; zakaz użycia tego samego klucza dla innej tożsamości zadania.
- `TeamRunEngineNetworkTest`: checkpoint musi zostać zapisany przed HTTP; brak zapisu blokuje wysłanie; błąd trwałego księgowania po udanej odpowiedzi pozostawia `USAGE_PENDING`.

### Ograniczenia i ostrożność

- Estymacja `UNAVAILABLE` może zawyżyć zużycie, jeśli proces zakończył się już po zapisaniu `REQUESTING`, ale jeszcze przed faktycznym wysłaniem POST. To celowy wariant fail-safe; checkpoint jest zapisywany bezpośrednio przed wywołaniem transportu.
- Gdy rejestr usage jest uszkodzony lub niedostępny, stan pozostaje pending i budżet nadal działa fail-closed; aplikacja nie usuwa ani nie nadpisuje uszkodzonej historii.
- Checkpoint nie zawiera promptów, odpowiedzi, załączników ani kluczy API.
- Zadania po awarii są oznaczane jako przerwane; automatyczne wznowienie pracy nie zostało włączone, aby uniknąć niejawnego ponownego wykonania lub kosztów.

### Aktualne liczby testów

- `ApiTokenTrackerTest.kt`: 28.
- `TaskCheckpointStoreTest.kt`: 11.
- `TeamRunEngineNetworkTest.kt`: 9.
- `ProviderClientNetworkTest.kt`: 14.
- Łącznie w tych czterech plikach: 62 testy; wszystkie **NOT RUN**.

### Status weryfikacji

- Kompilacja Android / Gradle: **NOT RUN**.
- JUnit/Robolectric: **NOT RUN**.
- Nie utworzono APK, nie zmieniono wersji, nie wykonano merge.
- Zmiany pozostają na branchu roboczym; `main` nie był modyfikowany.
- Companion Transport pozostaje BLOCKED.

Stan przed zapisaniem tego uzupełnienia:
- HEAD branch: b68a0733c774668b70128350a93fbc9731ed6a9a
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a


## Uzupełnienie — trwałość historii czatów, pamięci i wyników agentów (2026-11-06)

### 1. Historia rozmów — fail-closed i trwały zapis

Dodano `ChatHistoryStore` jako ścisłą warstwę dostępu do istniejącego dokumentu `chat_state/chats_v2` (bez migracji formatu i bez kasowania danych).

- Brak dokumentu jest prawidłowym stanem pierwszego uruchomienia; niepoprawny JSON, uszkodzony rekord rozmowy, wiadomość bez pól `role/text`, niepoprawne tablice ID/aktywności lub nieprawidłowy `currentId` są traktowane jako uszkodzenie.
- Odczyt uszkodzonego dokumentu zwraca stan błędu; mutacje nazw, usuwanie, dopisywanie wiadomości i aktywności agentów, przywracanie czatu oraz zapisywanie bieżącego ID są blokowane. Nie tworzymy nowego pustego dokumentu w miejsce nieczytelnego.
- Zapis `chats_v2` używa synchronicznego `commit()`; błędy są raportowane, a metody zapisu zwracają wynik trwałości.
- Nowa wiadomość użytkownika musi zostać trwale zapisana przed wysłaniem HTTP. Jeśli zapis się nie powiedzie, żądanie API nie jest wysyłane.
- Odpowiedź agenta/MASTER-a jest oznaczana jako `COMPLETED` lub `FAILED` dopiero po potwierdzeniu zapisu odpowiedzi w odpowiednim czacie. Jeżeli zapis historii nie powiedzie się, checkpoint pozostaje niekońcowy.
- Poprzedni czat otrzymuje odpowiedź w swoim własnym ID nawet wtedy, gdy użytkownik w międzyczasie otworzył inną rozmowę; jeśli zapis poprzedniego czatu jest niedostępny, nie odtwarzamy go pod błędnym ID.

### 2. Pojedynczy czat — checkpoint i idempotentne rozliczenie

Wcześniejszy mechanizm obejmował głównie batch agentów. Rozszerzono go na zwykłe zapytania czatu:

- Przed uruchomieniem workera zapisywany jest checkpoint `QUEUED`; niepowodzenie zapisu blokuje wysłanie API.
- Bezpośrednio przed każdym providerowym POST zapisywany jest `REQUESTING` z providerem, modelem, estymacją tokenów, oczyszczonym endpointem i kluczem `chat:<taskId>:<providerId>`.
- Sukces rozliczenia zapisuje `USAGE_RECORDED`; awaria trwałego księgowania po odpowiedzi zapisuje `USAGE_PENDING`.
- Niejednoznaczna awaria po możliwym przetworzeniu POST zapisuje estymowane usage jako `UNAVAILABLE` pod tym samym kluczem idempotencji. Nie uruchamia fallbacku po potencjalnie billable POST.
- Potwierdzona awaria przed przetworzeniem POST przywraca stan `RUNNING` przed ewentualnym fallbackiem. Jeśli nie da się utrwalić tego stanu, kolejny provider nie jest wywoływany.
- Po zapisaniu odpowiedzi w historii czatu zadanie otrzymuje stan terminalny. `USAGE_PENDING` pozostaje otwarty do odzyskania księgowania.

### 3. Pamięć zatwierdzona i dzienniki — ochrona przed nadpisaniem

Wykryto wspólny wzorzec: kilka magazynów zamieniało uszkodzony JSON na pustą listę, a kolejny zapis mógł bezgłośnie nadpisać wcześniejsze dane.

Zabezpieczono:
- `AiMemoryStore` — uszkodzona tablica pamięci blokuje add/remove; zapis zatwierdzonych wpisów używa `commit()` i zwraca wynik.
- `AgentOperationalMemoryStore` — uszkodzony rekord blokuje add/remove/removeBySource; odczyt nie generuje losowych nowych ID; zapis jest synchroniczny.
- `LearningJournalStore` — strict parser nie odrzuca uszkodzonych wierszy i nie nadpisuje dziennika; zapis jest synchroniczny. Zachowano kompatybilność starszych wpisów, w których pola tokenów jeszcze nie istniały.
- `QualityLearningStore` — mutacje ocen i propozycji są blokowane, gdy odpowiedni dziennik jest uszkodzony; zapisy używają `commit()`. Status `APPROVED` jest wycofywany, jeżeli nie uda się trwale zapisać pamięci wspólnej lub pamięci agentów.
- `syncApprovedAgentMemories()` odbudowuje pamięć wspólną i agentową z zatwierdzonych propozycji; jest wywoływana przed budową kontekstu każdego żądania MASTER-a.

### 4. Wyniki agentów — zapis przed terminalnym checkpointem

- `TeamRunEngine` otrzymał callback `onTaskResult`; dla udanego raportu jest wywoływany przed terminalnym statusem zadania.
- Tuż przed zapisem raportu utrwalany jest stan `RESULT_PERSIST_PENDING`. Po potwierdzeniu zapisu zadanie przechodzi do `COMPLETED`; jeśli zapis wyniku nie powiedzie się, pozostaje w stanie oczekującym.
- Główny batch MASTER-a zapisuje raporty od razu po zakończeniu poszczególnych agentów do istniejącego `TaskResultCache`, a nie dopiero po zakończeniu całego batcha. Cache nadal wymaga dokładnego fingerprintu, zgodności providera/modelu i zachowuje dotychczasowy TTL 72 h / limit 60 wpisów.
- Wyniki fallbacku nie zanieczyszczają cache skonfigurowanego profilu; raport pozostaje w dzienniku konkretnej rozmowy.
- `TaskResultCache` nie zamienia uszkodzonego JSON na pusty cache; niepoprawny wiersz blokuje mutację. Zapis jest synchroniczny, a typowe sekrety providerów są redagowane przed utrwaleniem raportu.
- Po restarcie `RESULT_PERSIST_PENDING` jest oznaczany jako `INTERRUPTED` bez ponownego księgowania usage. Jeśli cache został zapisany, dokładny fingerprint pozwala użyć istniejącego raportu przy kolejnym przebiegu.

### Testy regresyjne dodane/rozszerzone w tym etapie

| Plik testowy | Testy |
|---|---:|
| ChatHistoryStoreTest | 8 |
| AiMemoryStoreTest | 5 |
| AgentOperationalMemoryStoreTest | 6 |
| LearningJournalStoreTest | 4 |
| QualityLearningStoreTest | 14 |
| TaskCheckpointStoreTest | 13 |
| TaskResultCacheFingerprintTest | 7 |
| TeamRunEngineNetworkTest | 10 |
| **Razem w tych ośmiu plikach** | **67** |

Wszystkie powyższe testy: **NOT RUN**. Nie deklaruję PASS.

### Ograniczenia i pozostałe ryzyka

- Historia czatów nadal jest przechowywana jako jeden dokument JSON w SharedPreferences. Uszkodzenie dokumentu blokuje mutacje całej historii (fail-closed), a duży rozmiar historii może powodować niepowodzenie zapisu. Docelowo należy rozważyć migrację do Room/SQLite z transakcjami per rozmowa; nie wykonano jej w tym etapie.
- Dziennik aktywności agentów w każdej rozmowie nadal ma limit 150 wpisów. Cache raportów stanowi dodatkową ścieżkę odzyskiwania dla wyników z dokładnie zgodnym fingerprintem; nie jest to pełne wznowienie przerwanego batcha.
- Nie zapisujemy promptów ani pełnych raportów w `TaskCheckpointStore`; raporty pozostają w historii rozmowy/cache, a checkpointy przechowują wyłącznie metadane.
- Zadania po awarii nie są automatycznie ponawiane do providerów. Odzyskiwany jest stan, rozliczenie i możliwość ponownego użycia już zapisanego wyniku.

### Status weryfikacji

- Kompilacja Android / Gradle: **NOT RUN**.
- JUnit/Robolectric: **NOT RUN**.
- APK: nie utworzono.
- `versionName=0.6.54`, `versionCode=80` — bez zmian.
- Merge / zmiany na `main`: nie wykonano.
- Companion Transport: nadal **BLOCKED**.

Stan przed zapisaniem tego uzupełnienia:
- HEAD branch: 49c855f2ce60d59e12c7a86967172d8557cc9ae1
- HEAD main: 752149d40186144b0f458a32b5826c8928349c3a



## Uzupełnienie — deterministyczna kolejność checkpointów przy zmianie zegara systemowego (2026-11-07)

Dalsza kontrola etapu trwałości ujawniła rozbieżność między kontraktem dziennika a implementacją `TaskCheckpointStore.latest()`:
- `record()` dodaje każde nowe zdarzenie na początku dziennika;
- komentarz i wcześniejszy test zakładały, że kolejność dziennika jest autorytatywna;
- `latest()` dodatkowo sortowało zdarzenia według `timestamp`, co mogło odwrócić rzeczywistą kolejność, jeśli zegar urządzenia został cofnięty lub przestawiony.

Poprawka:
- usunięto sortowanie po znaczniku czasu;
- `latest()` zachowuje kolejność trwałego dziennika i wybiera pierwsze wystąpienie każdego `taskId`;
- dodano regresję `latestUsesJournalOrderWhenWallClockMovesBackwards`, w której nowsze zdarzenie RUNNING ma wcześniejszy czas systemowy niż poprzednie QUEUED.

Pliki:
- `app/src/main/java/pl/aimasterpro/app/core/TaskCheckpointStore.kt`
- `app/src/test/java/pl/aimasterpro/app/core/TaskCheckpointStoreTest.kt`

Weryfikacja:
- zmiany i test zapisano na branchu roboczym;
- nowy test nie został uruchomiony; JUnit/Robolectric i kompilacja Android pozostają **NOT RUN**;
- nie deklaruję PASS.

Stan po zapisaniu poprawki:
- HEAD branch: `2ad8ea497ecee812ff064f7da5b5fe834fc1b8c8`
- poprzedni commit kodu: `b6e22bcb552774262f7cbf792de7493e1085e45c`
- HEAD main: `752149d40186144b0f458a32b5826c8928349c3a` (bez zmian)
- liczba testów w ośmiu plikach z poprzedniego etapu: 68; wszystkie **NOT RUN**.
- APK, bump wersji, merge i Companion Transport: bez zmian; Companion Transport pozostaje **BLOCKED**.


## Uzupełnienie — anulowanie agenta podczas trwającego POST (2026-11-07)

Kontynuując zakres timeout/anulowanie, dodano osobną regresję dla przypadku, którego nie pokrywał dotychczasowy test timeoutu odpowiedzi: jawne anulowanie nadrzędnego zakresu, gdy serwer już odebrał POST agenta, ale jeszcze nie zwrócił odpowiedzi.

Test `cancellingInFlightPostRecordsUnknownUsageAndNeverStartsFallback`:
- uruchamia lokalny serwer testowy i czeka na odebranie pełnego POST;
- anuluje nadrzędny `RequestScope`, który obejmuje zakres potomny agenta;
- zwalnia odpowiedź serwera po anulowaniu;
- sprawdza, że koordynator kończy pracę, usage trafia do ścieżki `UNAVAILABLE` przez `onUnknownUsage`, a drugi provider/fallback nie otrzymuje żądania.

Plik:
- `app/src/test/java/pl/aimasterpro/app/agents/TeamRunEngineNetworkTest.kt`

Weryfikacja:
- test dodany do repozytorium, ale **NOT RUN**;
- nie deklaruję PASS ani poprawności runtime bez uruchomienia Gradle/Robolectric.

Stan przed aktualizacją checkpointu:
- testowy commit: `232aaed0da6b815274d0619cda53c3247aa7aa45`
- HEAD main pozostaje `752149d40186144b0f458a32b5826c8928349c3a`;
- nie zmieniono wersji aplikacji, APK ani main;
- Companion Transport nadal pozostaje **BLOCKED**.


## Uzupełnienie — synchronizacja odczytu rejestru usage z równoległym księgowaniem (2026-11-07)

Przegląd `ApiTokenTracker` wykazał, że modyfikacje `sessionRecords` są chronione monitorem obiektu (m.in. `recordQuery()`, `clearRecords()`, `planBatch()`), ale publiczny `loadAllRecordsFromStorage()` iterował po tej samej mutowalnej liście bez synchronizacji. Równoległy odczyt monitora/diagnostyki mógł więc wejść w konflikt z dopisywaniem rekordu usage i zakończyć się niespójnym snapshotem lub `ConcurrentModificationException`.

Poprawka:
- oznaczono `loadAllRecordsFromStorage()` jako `@Synchronized`, używając tego samego monitora co zapis i planowanie rezerwacji;
- dodano `concurrentUsageSnapshotsAreSerializedWithSessionRecordWrites`, który równolegle zapisuje 80 rekordów i wielokrotnie pobiera snapshoty, a następnie sprawdza kompletność wszystkich rekordów.

Pliki:
- `app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt`
- `app/src/test/java/pl/aimasterpro/app/core/ApiTokenTrackerTest.kt`

Weryfikacja:
- poprawka i test zapisane na branchu;
- test **NOT RUN**; kompilacja i JUnit/Robolectric nadal niepotwierdzone.

Stan przed aktualizacją checkpointu:
- poprawka źródłowa: `71a9aad9a3acfffcdfc7ac6d2828498605368251`
- test: `06cc24e413a83fb7e4184e2b555d721954fd6202`
- main bez zmian: `752149d40186144b0f458a32b5826c8928349c3a`.


## Bilans bieżącej kontynuacji (2026-11-07)

- `TeamRunEngineNetworkTest`: 11 testów po dodaniu regresji anulowania POST.
- `ApiTokenTrackerTest`: 29 testów po dodaniu regresji współbieżnych snapshotów.
- Oba nowe testy pozostają **NOT RUN**.
- Nie należy sumować tych liczb z poprzednimi sekcjami changeloga jako unikalnej liczby testów całego projektu — część zestawów była już wcześniej opisywana w checkpointach.
- Brak kompilacji, APK i potwierdzonego uruchomienia JUnit/Robolectric pozostaje blokadą uznania poprawek za zweryfikowane.


## Korekta audytu — usunięcie zduplikowanej adnotacji synchronizacji (2026-11-07)

Kontrola zmian z ostatniego etapu wykazała błąd wprowadzony przy dodawaniu synchronizacji `loadAllRecordsFromStorage()`: przed deklaracją metody znalazły się dwie identyczne adnotacje `@Synchronized`. Jedna adnotacja jest wystarczająca; duplikat usunięto, zachowując synchronizację odczytu z zapisami i rezerwacjami korzystającymi z monitora obiektu.

Plik:
- `app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt`

Commit poprawki: `58e519a1e723999913649ede892ab73d43960774`.

Weryfikacja: poprawkę zapisano na branchu roboczym. Nie uruchomiono kompilacji Kotlin/Gradle ani testów; nie oznaczać jako potwierdzonego rozwiązania błędu kompilacji do czasu uruchomienia builda. Test `concurrentUsageSnapshotsAreSerializedWithSessionRecordWrites` pozostaje NOT RUN.


## Etap weryfikowalności — dodanie ścieżki CI Android (2026-11-07)

Po sprawdzeniu konfiguracji projektu potwierdzono:
- Android Gradle Plugin: `8.13.2`;
- Gradle distribution: `8.13`;
- JDK target/source: `17`;
- `compileSdk = 36`;
- testy jednostkowe: JUnit 4.13.2 i Robolectric 4.11.1;
- repozytorium nie zawierało `gradle-wrapper.jar`, dlatego lokalne `./gradlew` nie było kompletną ścieżką uruchomienia.

Dodano workflow `.github/workflows/android-audit.yml`:
- uruchamia się po push na branch audytowy, przy pull request do `main` oraz ręcznie;
- instaluje JDK 17, Android SDK API 36 i Gradle 8.13;
- uruchamia `:app:testDebugUnitTest` oraz `:app:assembleDebug`;
- zachowuje raporty testowe jako artefakt na 14 dni;
- nie publikuje APK ani nie wykonuje release/merge.

Commit workflow: `7a461943b57f0102c3b6c50b3dd37890ddb23bfd`.

Status: dodanie workflow nie jest równoznaczne z zaliczeniem builda. Oczekujemy na rzeczywisty wynik GitHub Actions; do tego czasu testy i kompilacja pozostają NOT VERIFIED. Brak wrapper JAR nadal pozostaje w repozytorium, a workflow świadomie używa zainstalowanego Gradle 8.13 bezpośrednio.


### Korekta konfiguracji CI po pierwszym uruchomieniu

Pierwszy run workflow (`42310934463`) zakończył się błędem na kroku `Set up Android SDK`, zanim uruchomiono Gradle. Log potwierdził, że akcja `android-actions/setup-android@v3` domyślnie próbowała instalować usunięty pakiet SDK `tools` (`Failed to find package 'tools'`). Nie jest to wynik kompilacji aplikacji ani testów.

Skorygowano workflow: jawnie ustawiono `accept-android-sdk-licenses: true` oraz pakiety `platforms;android-36`, `build-tools;36.0.0` i `platform-tools`, wyłączając przestarzały domyślny pakiet `tools`.

Commit korekty CI: `4bdf543b032a723704970ccb10169700b2a7be7e`.

Oczekiwany jest kolejny run na najnowszym HEAD. Dopiero wynik kroków Gradle będzie miarodajny dla kompilacji i testów.


### Druga korekta CI — format listy pakietów SDK

Drugi run (`42311104170`) ponownie zatrzymał się przed Gradle. Tym razem log wykazał, że `setup-android` przekazał wielowierszową wartość `packages` jako jeden argument do `sdkmanager`, więc SDK Manager potraktował trzy pakiety jako jedną nieistniejącą nazwę. Skorygowano konfigurację zgodnie z kontraktem akcji: `packages: ''` wyłącza pakiety domyślne, a osobny krok powłoki wywołuje `sdkmanager --install` z trzema odrębnymi argumentami.

Dodatkowo ustawiono `cancel-in-progress: false`, aby kolejne commity dokumentacyjne nie przerywały aktywnego audytu, oraz dodano `paths-ignore` dla plików Markdown w push/PR, żeby same aktualizacje checkpointu nie uruchamiały ponownie pełnego builda.

Commit korekty workflow: `354e3b73aed8a2f7f726ce51c84cf9c9ef64bf58`.

Wciąż brak wyniku Gradle/JUnit. Runy 1–4 nie potwierdzają kompilacji ani testów: pierwszy został anulowany, kolejne dwa zakończyły się błędem konfiguracji SDK, a run 4 był próbą tej drugiej konfiguracji.


## Etap 8 — zamykanie żądań HTTP przy niszczeniu Activity (2026-11-07)

Kontrola lifecycle MainActivity wykazała, że `onDestroy()` przerywał executor przez `shutdownNow()`, ale nie rozłączał jawnie połączeń `HttpURLConnection`. Sam interrupt wątku nie gwarantuje natychmiastowego przerwania blokującego odczytu sieciowego; żądanie mogło więc pozostać aktywne do timeoutu transportu po zniszczeniu Activity.

Poprawka w `MainActivity.onDestroy()`:
- jeśli ProviderClient został zainicjalizowany, wywołuje `cancelActiveRequests()`, aby unieważnić zakresy i rozłączyć aktywne połączenia należące do tej instancji Activity;
- czyści referencję aktywnego zakresu czatu;
- przerywa referencję aktywnego workera;
- dopiero potem wyłącza executor.

Plik: `app/src/main/java/pl/aimasterpro/app/MainActivity.kt`.
Commit: `403f595895094714f66fc4aa258af01b2ab5dfa7`.

Uzasadnienie: ProviderClient ma już osobne testy sieciowe globalnego anulowania i rozłączania aktywnych POST; nie dodano duplikującego testu transportu. Integracja lifecycle MainActivity nie została jeszcze potwierdzona testem Robolectric. Weryfikacja pozostaje NOT VERIFIED do wyniku CI.


## Uzupełnienie — zakres anulowania pobierania katalogu modeli (2026-11-07)

Dalszy przegląd lifecycle wykazał, że `discoverModelDetails()` korzystało z `requestWithRetry()` bez ustawienia `requestScopeLocal`. Pobieranie katalogu modeli było więc operacją niezależną od zakresu czatu, ale nie miało własnego zakresu, który `cancelActiveRequests()` mógłby unieważnić i rozłączyć podczas niszczenia Activity.

Zmiana:
- `discoverModelDetails(config, scope)` otrzymuje niezależny zakres domyślnie tworzony przez `beginRequestScope()`;
- zakres jest ustawiany na czas całego pobierania i przywracany w `finally`, aby nie naruszyć ewentualnego zewnętrznego kontekstu wątku;
- rozpoczęcie nowego czatu nadal nie anuluje trwającego discovery;
- globalne anulowanie / teardown unieważnia teraz także pobieranie katalogu i uniemożliwia retry po przerwaniu.

Testy:
- zmieniono nazwę testu niezależności discovery na `beginningNewChatScopeDoesNotCancelIndependentModelDiscovery`;
- dodano `cancellingGlobalRequestsDisconnectsModelDiscoveryWithoutRetry`: lokalny serwer wstrzymuje odpowiedź GET, test wykonuje globalne anulowanie i sprawdza szybkie zakończenie, kategorię `CANCELLED` oraz dokładnie jedno żądanie bez retry.

Pliki:
- `app/src/main/java/pl/aimasterpro/app/network/ProviderClient.kt`
- `app/src/test/java/pl/aimasterpro/app/network/ProviderClientNetworkTest.kt`

Commity:
- implementacja: `5694a3f1b1d0e94c7a880047712f0788bb437905`
- test: `b28815899b207c0dc0cd7fe8dbd75109b2abefa3`
- zachowanie poprzedniego zakresu ThreadLocal i formatowanie: `2137387f9fc9a7f09ed181619ab7691116ed12f1`
- korekta nazwy testu: `21b532705940f04fe30850ee08605abacdb620af`

Weryfikacja:
- zmiany zapisane na branchu roboczym;
- testy i kompilacja tej poprawki pozostają **NOT RUN / NOT VERIFIED**;
- nowy run CI dla HEAD `21b532705940f04fe30850ee08605abacdb620af` oczekuje na wykonanie (run `42312323287`, status pending);
- starszy run `42311168794` nadal raportował krok Gradle jako in_progress przy ostatnim odczycie; nie jest wynikiem PASS;
- run `42311354989` oraz pośrednie runy wyzwolone kolejnymi commitami zostały anulowane przez nowsze uruchomienia;
- `main` pozostaje na `752149d40186144b0f458a32b5826c8928349c3a`; branch roboczy jest 227 commitów ahead, 0 behind;
- nie wykonano merge, APK, release ani zmian na `main`; Companion Transport pozostaje **BLOCKED**.


### Stan repozytorium po aktualizacji checkpointu

- Aktualny HEAD brancha roboczego: `952b367479657e094cb944068b4ed2b13b293ac9` (aktualizacja dokumentacji audytu).
- Ostatni commit kodu/testu objęty najnowszym runem CI: `21b532705940f04fe30850ee08605abacdb620af`.
- Porównanie z `main`: 228 commits ahead, 0 behind.
- `main` nadal: `752149d40186144b0f458a32b5826c8928349c3a`.
- Run CI `42311168794` pozostaje `in_progress` na kroku Gradle; najnowszy run `42312323287` pozostaje `pending` w kolejce. Brak potwierdzonego wyniku testów i kompilacji.


## Etap 9 — walidacja integralności sum tokenów w trwałym rejestrze (2026-11-07)

Przegląd `ApiTokenTracker.readPersistedRecords()` wykazał lukę w walidacji danych historycznych: parser odrzucał ujemne liczniki i nieprawidłowe koszty, ale akceptował rekord, w którym `totalTokens` nie odpowiadało sumie `inputTokens + outputTokens`. Taki rekord mógł zaniżyć dzienne wykorzystanie limitu, mimo że sam JSON był poprawny.

Zmiana:
- obliczanie oczekiwanej sumy przez `Math.addExact(input, output)` (również ochrona przed przepełnieniem `Long`);
- parser odrzuca rekord, jeśli `totalTokens != inputTokens + outputTokens`;
- ponieważ `readPersistedRecords()` zwraca `null` dla nieprawidłowego rejestru, istniejąca polityka fail-closed blokuje nowe rezerwacje i zapisy zamiast nadpisywać potencjalnie błędne dane.

Test:
- `inconsistentPersistedTokenTotalMakesLedgerUnhealthyAndBlocksWrites` wstawia syntaktycznie poprawny rekord z niezgodną sumą;
- sprawdza, że planowanie budżetu blokuje zadanie;
- sprawdza, że nowy zapis nie nadpisuje uszkodzonego rejestru i oryginalny JSON pozostaje zachowany.

Pliki:
- `app/src/main/java/pl/aimasterpro/app/core/ApiTokenTracker.kt`
- `app/src/test/java/pl/aimasterpro/app/core/ApiTokenTrackerTest.kt`

Commity:
- walidacja: `9ee44f1221c25970834ec3a067d1eec3bf9dd0b6`
- test: `b861d6a870f01484e5a7aeda03d2bc7bd797de84`

Weryfikacja:
- zmiany zapisane na branchu audytowym;
- test nowej regresji i kompilacja nadal **NOT RUN / NOT VERIFIED** — oczekujemy na GitHub Actions;
- nie zmieniono `main`, nie wykonano merge, APK ani release; Companion Transport pozostaje **BLOCKED**.


### CI — usunięcie blokady kolejki przez zawieszony run

Run `42311168794` pozostawał w stanie `in_progress` bez aktualizacji od `2026-11-06T23:20:01Z`, mimo że nowsze runy były anulowane lub pozostawały w kolejce. Najnowszy run dla kodu rejestru tokenów (`42312958184`) został anulowany po pojawieniu się kolejnego push, a run dla aktualnej konfiguracji workflow (`42313016805`) jest `pending` i nie ma jeszcze utworzonego joba.

Zmiana:
- `cancel-in-progress` zmieniono z `false` na `true` w grupie concurrency workflow;
- `paths-ignore` dla Markdown pozostaje aktywne, więc aktualizacje checkpointu nie powinny anulować ani uruchamiać builda;
- celem jest automatyczne zastępowanie starszych, nieaktualnych uruchomień przez najnowszy audyt, zamiast blokowania kolejki przez zawieszony run.

Commit workflow: `046eefc3707964d4d39ecba0ff38a88a75a8c042`.

Stan po zmianie:
- run `42313016805` dla HEAD workflow: `pending`; brak jobów, zatem brak wyniku Gradle;
- stary run `42311168794` nadal raportuje `in_progress`, bez zmiany statusu;
- nie ma jeszcze potwierdzonego test/build PASS. Jeśli kolejka pozostanie zablokowana, potrzebna będzie ręczna interwencja w GitHub Actions (anulowanie zawieszonego runu) lub alternatywne uruchomienie CI.


## Etap 10 — usunięcie zduplikowanego callbacku w integracji TeamRunEngine (2026-11-07)

Przegląd miejsca wywołania `TeamRunEngine.runBatch()` w `MainActivity.kt` wykazał dwa identyczne argumenty nazwane `onRequestStarting` w jednym wywołaniu. Kotlin nie dopuszcza wielokrotnego przekazania tego samego argumentu nazwanego — jest to błąd kompilacji.

Zmiana:
- usunięto zduplikowany callback;
- pozostawiono pojedynczy callback, który zapisuje trwały checkpoint `REQUESTING` przed rozpoczęciem żądania HTTP;
- zachowano pola identyfikacji zadania, providera, modelu, estymacji tokenów, klucza idempotencji i bezpiecznego Base URL;
- potwierdzono w odczytanym pliku, że w wywołaniu głównego `runBatch()` pozostało dokładnie jedno `onRequestStarting`. Drugie wystąpienie w pliku należy do odrębnego wywołania peer review.

Plik:
- `app/src/main/java/pl/aimasterpro/app/MainActivity.kt`

Commit: `8387ee627add4ef86e37b901aea0fa3d8a627dbc`.

Weryfikacja:
- poprawka zapisana na branchu audytowym;
- kompilacja Kotlin/Gradle nadal **NOT VERIFIED** do czasu wykonania CI;
- workflow ma `cancel-in-progress: true`, aby nowszy kod zastępował nieaktualne runy; `paths-ignore` dla Markdown chroni przed niepotrzebnym restartem przy aktualizacji tego checkpointu;
- brak merge, APK, release i zmian `main`; Companion Transport pozostaje **BLOCKED**.


### CI po usunięciu zduplikowanego argumentu

- Workflow run: `42313083194` — [GitHub Actions](https://github.com/krzysztofchojnacki5-ship-it/aimaster_071_work/actions/runs/42313083194).
- SHA uruchomienia: `8387ee627add4ef86e37b901aea0fa3d8a627dbc` (zawiera usunięcie podwójnego `onRequestStarting` oraz wcześniejsze poprawki rejestru tokenów).
- JDK 17, Android SDK API 36, instalacja pakietów SDK i Gradle 8.13: **SUCCESS**.
- Krok `Run unit tests and compile debug variant`: **IN_PROGRESS** przy ostatnim odczycie; nie ma jeszcze raportu testów ani wyniku kompilacji.
- Aktualny HEAD brancha po dopisaniu dokumentacji: `a0c5d4ec10f8295980db92940b829b177b11cc8a`.
- Porównanie z `main`: 236 commits ahead, 0 behind; `main` nadal `752149d40186144b0f458a32b5826c8928349c3a`.
