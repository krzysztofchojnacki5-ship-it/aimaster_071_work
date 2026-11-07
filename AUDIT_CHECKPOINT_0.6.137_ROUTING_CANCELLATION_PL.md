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


## Etap 11 — blokada importu przy nieuzgodnionym receipt patcha (2026-11-07)

Przegląd transakcji workspace ujawnił brak symetrycznej blokady: applyPreview() odmawiał kolejnego patcha, gdy istniał nierozliczony patchReceiptFile, ale importZip() po odzyskaniu dziennika swapu nie sprawdzał tego receipt. Import ZIP mógł więc zastąpić workspace pomiędzy trwałym zatwierdzeniem patcha a uzgodnieniem jego wyniku z rejestrem ChangeSet. Stary receipt pozostałby wtedy związany z poprzednią identity, a odzyskiwanie hosta mogłoby zostać zatrzymane przez niezgodność identity.

Zmiana:
- ProjectWorkspaceStore.importZip() po recoverInterruptedSwap() sprawdza teraz, czy istnieje receipt patcha;
- jeśli receipt nie został uzgodniony, import kończy się bez podmiany workspace i z czytelnym komunikatem;
- receipt oraz snapshot pozostają dostępne dla hostowego mechanizmu recovery;
- blokada jest w warstwie magazynu, a nie wyłącznie w UI, więc obejmuje wszystkie wywołania importZip().

Test regresyjny:
- refusesImportWhilePatchReceiptIsAwaitingChangeSetReconciliation;
- wykonuje poprawny patch z changeSetId i chatId, pozostawia receipt nieuzgodniony, próbuje importować kolejny ZIP;
- sprawdza odrzucenie importu, zachowanie nowej treści, niezmienioną workspace identity, utrzymanie pending transaction oraz poprawność integralności.

Pliki:
- app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceStore.kt
- app/src/test/java/pl/aimasterpro/app/core/ProjectWorkspaceStoreApplyTest.kt

Commity:
- blokada importu: 6c71ac8cd69b7eb73f0e75a8069b543db078709e
- test regresyjny: 0cb89f62550299e3343cf446360b0aa590301d7c

Weryfikacja:
- pliki ponownie odczytane z brancha; potwierdzono obecność guardu i testu;
- test nie został jeszcze wykonany lokalnie ani w CI;
- nowy workflow run 42315138948 dla SHA 0cb89f62550299e3343cf446360b0aa590301d7c ma status queued;
- poprzedni run 42313083194 zakończył się jako cancelled po uruchomieniu nowszej wersji workflow; nie był wynikiem testów PASS;
- brak merge, APK, release i zmian main; Companion Transport pozostaje BLOCKED.


## Etap 12 — receipt-gated restore i jawnie autoryzowany rollback (2026-11-07)

Kontynuacja etapu 11 wykazała, że analogiczna luka istniała w publicznym restoreSnapshot(): ręczne przywrócenie snapshotu mogło zastąpić workspace, mimo że receipt poprzedniego patcha nadal czekał na uzgodnienie. Taki restore mógł doprowadzić do tej samej niezgodności identity co import ZIP.

Zmiana:
- zwykłe restoreSnapshot() odmawia teraz działania, gdy istnieje nierozliczony patch receipt;
- wydzielono restoreSnapshotInternal(), używane przez dwa jawne, kontrolowane wejścia;
- dodano restoreSnapshotForPatchRollback(), które wymaga dokładnej zgodności changeSetId, transactionId i snapshotId oraz trwałego rollbackRequested=true;
- recoverRequestedPatchRollback() korzysta z tej autoryzowanej ścieżki;
- MainActivity podczas kompensacji po nieudanym zapisie ChangeSet przekazuje dokładny identyfikator transakcji do restoreSnapshotForPatchRollback();
- zwykła weryfikacja projektu nadal korzysta z restoreSnapshot(); w obecności pending receipt rollback jest bezpiecznie blokowany zamiast nadpisywać transakcję oczekującą na recovery.

Test regresyjny:
- manualRestoreCannotBypassPendingPatchReceiptButExplicitRollbackCan;
- sprawdza, że ręczny restore jest odrzucony, oryginalny nowy workspace pozostaje nienaruszony, a jawny rollback po trwałym zapisaniu żądania przywraca dokładny snapshot, oznacza receipt jako rolledBack i dopiero potem pozwala go uzgodnić.

Pliki:
- app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceStore.kt
- app/src/main/java/pl/aimasterpro/app/MainActivity.kt
- app/src/test/java/pl/aimasterpro/app/core/ProjectWorkspaceStoreApplyTest.kt

Commity:
- blokada zwykłego restore i ścieżka autoryzowanego rollbacku: c0fa19e7e0f48a06dc831a94d6e31cdb977c7c03
- integracja MainActivity: fad2a0e4430425f1aa3a05659f07952a47b305f0
- test regresyjny: 47484ab862994fc77b44f5586646c522a75a9e45

Weryfikacja:
- ponownie odczytano zmienione pliki i potwierdzono, że wszystkie wywołania rollbacku używają autoryzowanej ścieżki; zwykłe przywracanie pozostaje zablokowane przy pending receipt;
- testy pozostają NOT VERIFIED do czasu zakończenia CI;
- najnowszy workflow run 42315490004 dla SHA 47484ab862994fc77b44f5586646c522a75a9e45 ma status in_progress; wcześniejsze uruchomienia pośrednich commitów zostały anulowane przez concurrency;
- brak merge, APK, release i zmian main; Companion Transport pozostaje BLOCKED.


## Etap 13 — kontrola identity wewnątrz autoryzowanego rollbacku (2026-11-07)

Dodatkowa kontrola ścieżki rollbacku wykazała, że autoryzowana metoda sprawdzała dokładne identyfikatory receipt i flagę rollbackRequested, ale polegała wyłącznie na kontroli identity wykonywanej przez jej obecnych wywołujących. Warstwa magazynu powinna egzekwować tę granicę samodzielnie, aby przyszły nowy caller nie mógł przypadkowo nadpisać workspace o trzeciej identity.

Zmiana:
- restoreSnapshotForPatchRollback() wymaga teraz, aby aktualna workspace identity nadal była dokładnie równa workspaceIdentity zatwierdzonego patcha;
- przy identity bazowej lub trzeciej transakcji metoda odmawia działania; przypadek już wykonanego restore obsługuje recoverRequestedPatchRollback() osobną gałęzią;
- nie polegamy wyłącznie na kontroli wykonanej w MainActivity.

Test:
- authorizedRollbackRefusesAThirdWorkspaceIdentity;
- zapisuje żądanie rollbacku, zmienia manifest na trzecią identity i sprawdza, że rollback zostaje odrzucony, treść bieżącego katalogu nie jest nadpisana, a receipt pozostaje do diagnostyki.

Pliki:
- app/src/main/java/pl/aimasterpro/app/core/ProjectWorkspaceStore.kt
- app/src/test/java/pl/aimasterpro/app/core/ProjectWorkspaceStoreApplyTest.kt

Commity:
- kontrola identity: 17169d9b763a956f8a5aa39b9c49c5e2364554f5
- test regresyjny: ab282f310b7a4bb44f3a23407c44fa7eea965925

Weryfikacja:
- kod i test zapisane na branchu; test nie został jeszcze wykonany;
- najnowszy CI po zmianach Etapu 12 może zostać anulowany przez push kolejnego kodu; oczekujemy na run obejmujący Etap 13;
- brak merge, APK, release i zmian main; Companion Transport pozostaje BLOCKED.


### CI po Etapie 13

- Aktualny kod/test objęty runem: SHA ab282f310b7a4bb44f3a23407c44fa7eea965925.
- Workflow run: 42315729083; job 128227729164.
- JDK 17, Android SDK, API 36 i Gradle 8.13: SUCCESS.
- Krok testów jednostkowych i kompilacji debug: IN_PROGRESS; raportów i logów końcowych jeszcze nie udostępniono.
- Późniejszy commit dokumentacji checkpointu nie zmienia kodu i jest ignorowany przez paths-ignore.


### Korekta testu Etapu 13 i kolejka CI

Test authorizedRollbackRefusesAThirdWorkspaceIdentity został doprecyzowany: zamiast niepoprawnego manifestu używa teraz manifestu poprawnego, wygenerowanego przez drugi ProjectWorkspaceStore. Dzięki temu sprawdza rzeczywistą, trzecią identity, a nie tylko stan uszkodzonego manifestu.

- Commit testu: 5dd1c7abac833a81916f2c322a1b85f9a5e49349.
- Workflow run dla tego SHA: 42316019301 — PENDING przy ostatnim odczycie.
- Run 42315729083 dla poprzedniej wersji testu nadal był IN_PROGRESS; nowszy run powinien go zastąpić przez concurrency.
- Test i kompilacja pozostają NOT VERIFIED.


## Etap 14 — weryfikacja rzeczywistej integralności przed rollbackiem (2026-11-07)

Kontrola Etapu 13 została rozszerzona: manifest identity może pozostać taka sama po zewnętrznej modyfikacji pliku, ponieważ identity jest skrótem manifestu, a nie ponownym skrótem całego drzewa. Sama zgodność identity nie wystarcza więc do bezpiecznego rollbacku.

Zmiana:
- restoreSnapshotForPatchRollback() oprócz exact identity sprawdza teraz verifyCurrentWorkspaceInternal().valid;
- rollback jest blokowany, jeśli choć jeden plik jest zmieniony, brakujący, dodatkowy lub ma niebezpieczne dowiązanie;
- chroni to również przed nadpisaniem plików zmienionych po zatwierdzeniu patcha, nawet gdy manifest pozostał ten sam.

Test:
- authorizedRollbackRefusesTamperedFilesEvenWhenManifestIdentityMatches;
- zmienia treść pliku po trwałym zapisaniu żądania rollbacku, pozostawiając manifest bez zmian;
- sprawdza, że identity nadal odpowiada patchowi, ale kontrola integralności blokuje restore i pozostawia zmienioną treść oraz receipt do ręcznego odzyskania.

Commity:
- kontrola integralności: d2e7fb50f16249fa139d331efa2f19b2f6755c45
- test regresyjny: e29615a4a23ba3d4208183eebd3a3e3b0eeaa039

Weryfikacja:
- pliki zapisane na branchu; test oczekuje na CI;
- workflow run 42316275613 dla SHA d2e7fb50f16249fa139d331efa2f19b2f6755c45 był PENDING przy ostatnim odczycie;
- brak merge, APK, release i zmian main; Companion Transport pozostaje BLOCKED.


## Etap 15 — zachowanie metadanych REQUESTING w kolejnych checkpointach (2026-11-07)

Przegląd odzyskiwania TeamRunEngine potwierdził lukę: callback onRequestStarting zapisuje pełne metadane REQUESTING, ale późniejsze onTaskState(task, "USAGE_PENDING", ...) tworzy nowy Event z domyślnymi zerowymi estymacjami, pustym idempotencyKey i pustym baseUrl. TaskCheckpointStore.record() wcześniej zastępował najnowszy rekord bez zachowania tych pól. Startup recovery w MainActivity mogło więc zaksięgować UNAVAILABLE z zerowymi estymacjami i bez pierwotnego klucza idempotencji.

Zmiana:
- TaskCheckpointStore.record() zachowuje request-only metadata z poprzedniego zdarzenia dla tego samego taskId, gdy późniejszy stan nie dostarcza tych pól;
- zachowywane są providerId/model, estymacje input/output, idempotencyKey i bezpieczny baseUrl;
- nowe niepuste wartości z bieżącego zdarzenia nadal mają pierwszeństwo;
- journal order, terminal-state guard i processId pozostają bez zmian.

Test:
- usagePendingStatePreservesRequestMetadataForRestartReconciliation;
- zapisuje REQUESTING z pełnymi danymi, potem USAGE_PENDING bez pól request-only;
- sprawdza zachowanie danych w latest() i przekazanie ich do callbacku recoverStaleRunning() po symulacji poprzedniego procesu.

Pliki:
- app/src/main/java/pl/aimasterpro/app/core/TaskCheckpointStore.kt
- app/src/test/java/pl/aimasterpro/app/core/TaskCheckpointStoreTest.kt

Commity:
- zachowanie metadanych: f63f5204a1f20ff876fc2d6d94163f27babdc0e2
- test regresyjny: 55f5b1bc8939e56a9537e27b6dbb319b670d2cdd

Weryfikacja:
- kod i test zapisane; nie wykonano jeszcze testów lokalnych;
- workflow run 42316466914 dla SHA 55f5b1bc8939e56a9537e27b6dbb319b670d2cdd ma status PENDING przy ostatnim odczycie;
- brak merge, APK, release i zmian main; Companion Transport pozostaje BLOCKED.


## Etap 16 — fail-closed przy niekompletnych metadanych odzyskiwania usage (2026-11-07)

Po zachowaniu metadanych w Etapie 15 dodano kontrolę dla starszych/uszkodzonych checkpointów, w których tych pól nie ma. Startup recovery wcześniej używał zastępczego idempotencyKey i mimo zerowych estymacji mógł zapisać UNAVAILABLE z niepełnym usage, co groziło zaniżeniem rozliczenia.

Zmiana:
- TaskCheckpointStore.hasRecoverableUsageMetadata() wymaga providerId, model, dodatnich estymacji input/output i niepustego idempotencyKey;
- MainActivity nie wykonuje automatycznego księgowania, gdy którykolwiek z tych elementów jest nieobecny;
- taki checkpoint pozostaje INTERRUPTED_USAGE_PENDING do bezpiecznego wyjaśnienia/ponowienia; nie zapisujemy zera ani zgadywanego klucza;
- dla kompletnych metadanych recovery używa dokładnie zachowanego idempotencyKey.

Test:
- automaticUsageRecoveryRequiresCompleteRequestMetadata;
- sprawdza odrzucenie brakujących estymacji, klucza i modelu oraz akceptację kompletnego zestawu.

Pliki:
- app/src/main/java/pl/aimasterpro/app/core/TaskCheckpointStore.kt
- app/src/main/java/pl/aimasterpro/app/MainActivity.kt
- app/src/test/java/pl/aimasterpro/app/core/TaskCheckpointStoreTest.kt

Commity:
- walidacja kompletności metadanych: cd63fbf09999476284ba49b079057bfe8d7a923d
- fail-closed w startup recovery: 79b64cd661efe0e7889ad598752fe306bd3bdccb
- test regresyjny: 34a85b2ae984df0553281418a9a1839b1ba044d1

Weryfikacja:
- test i kod zapisane na branchu; brak potwierdzonego wykonania;
- workflow run 42316584880 dla SHA 34a85b2ae984df0553281418a9a1839b1ba044d1 ma status PENDING przy ostatnim odczycie;
- brak merge, APK, release i zmian main; Companion Transport pozostaje BLOCKED.


### Aktualizacja CI — Etap 16

- Run 42316584880 dla kodu/testu SHA 34a85b2ae984df0553281418a9a1839b1ba044d1 przeszedł konfigurację JDK 17, Android SDK API 36 i Gradle 8.13.
- Krok :app:testDebugUnitTest :app:assembleDebug: IN_PROGRESS.
- Run obejmuje Etap 15 i Etap 16; późniejszy commit checkpointu jest wyłącznie dokumentacyjny.
- Nie ma jeszcze potwierdzonego wyniku PASS.


### Przegląd współbieżności historii czatu — bez zmiany kodu

Sprawdzono dokładniej wcześniejszy trop dotyczący możliwego wyścigu zapisu wiadomości i aktywności agentów. W MainActivity już istnieje wspólna synchronizacja instancyjna:
- persistMessages() jest @Synchronized;
- persistAgentActivity() jest @Synchronized;
- appendAgentActivityForChat() jest @Synchronized;
- appendToStoredChat() jest @Synchronized.

Ponieważ te ścieżki używają tego samego monitora MainActivity, nie potwierdzono zgłoszonego wyścigu dla tych operacji. Nie wprowadzono zbędnej zmiany. Dodatkowo potwierdzono, że TeamRunEngine sprawdza boolean z onRequestStarting i nie wysyła POST, jeśli trwały checkpoint REQUESTING nie został zapisany.


## Etap 17 — niezależne archiwum raportów agentów i recovery po awarii zapisu (2026-11-07)

Przegląd trwałości wyników ujawnił, że dotychczasowy TaskResultCache pełni wyłącznie funkcję cache'u ponownego użycia (TTL 72 h, maksymalnie 60 wpisów), a dziennik aktywności rozmowy ogranicza się do 150 wpisów. Wynik z providera fallback był celowo wykluczany z cache'u, aby nie przypisać go błędnie do skonfigurowanego modelu. Oznaczało to, że nie istniał osobny, niezależny od cache'u rejestr pełnych raportów.

Zmiana:
- dodano TaskResultArchive jako osobny, trwały magazyn raportów po taskId; przechowuje agentId, pełny raport po sanitizacji, rzeczywisty providerId/model i czas zapisu;
- archiwum ma limit 80 najnowszych raportów i odrzuca pusty lub zbyt duży raport zamiast po cichu go obcinać;
- walidacja archiwum jest fail-closed: uszkodzony JSON, nieprawidłowe typy, duplikaty taskId lub niepoprawne pola blokują zapis zamiast zastąpić dane pustą listą;
- wszystkie trzy ścieżki TeamRunEngine w MainActivity (główny zespół, peer review i recenzja patcha) zapisują wynik do archiwum przed potwierdzeniem onTaskResult;
- w głównej ścieżce raport fallback jest dodatkowo cache'owany wyłącznie pod fingerprintem rzeczywistej konfiguracji fallbacku, nigdy pod tożsamością przypisanego modelu;
- awaria cache nie oznacza już utraty raportu, jeśli archiwum potwierdziło commit; awaria archiwum zwraca false, pozostawia RESULT_PERSIST_PENDING i jest logowana;
- startup recovery sprawdza archiwum dla RESULT_PERSIST_PENDING: jeśli raport istnieje, checkpoint jest uzgadniany do COMPLETED bez ponownego żądania API i bez ponownego księgowania usage; jeśli raportu nie ma, stan pozostaje INTERRUPTED z diagnostycznym opisem.

Testy:
- TaskResultArchiveTest: trwały zapis/odczyt, upsert po taskId, redakcja sekretów, fail-closed przy uszkodzonym archiwum, odrzucanie pustych i zbyt dużych raportów;
- TaskCheckpointStoreTest.resultPersistPendingTaskIsCompletedWhenDurableArchiveAlreadyContainsReport: symuluje śmierć procesu po commit archiwum, ale przed terminalnym checkpointem; potwierdza recovery do COMPLETED bez callbacku rozliczania usage;
- istniejący test bez wpisu w archiwum nadal potwierdza, że RESULT_PERSIST_PENDING zostaje INTERRUPTED.

Pliki:
- app/src/main/java/pl/aimasterpro/app/core/TaskResultArchive.kt
- app/src/test/java/pl/aimasterpro/app/core/TaskResultArchiveTest.kt
- app/src/main/java/pl/aimasterpro/app/MainActivity.kt
- app/src/main/java/pl/aimasterpro/app/core/TaskCheckpointStore.kt
- app/src/test/java/pl/aimasterpro/app/core/TaskCheckpointStoreTest.kt

Commity:
- archiwum: f0e5807dc91e09161e47af18a8c0570b2bf295c2
- testy archiwum: 835d077cc4b2e977c064fc0ac8fef033a1a38364
- integracja trzech ścieżek TeamRunEngine i rozdzielenie cache/archiwum: 1993587d395882b4fcd6bee56ed85aae92d8a4bc
- recovery checkpointu z archiwum: 5dadd0909815377448b689efdc3d6a3dfd36a08c
- test recovery: ad8a0d47fe73f51637f32ef01e8c708a43f26cad
- ścisła walidacja typów w archiwum: 93b99e1f6b6fed54695bb28e3c7368abd572a3d5

Weryfikacja:
- pliki źródłowe i testy ponownie odczytane z brancha; potwierdzono trzy callbacki onTaskResult dla trzech ścieżek runBatch;
- brak lokalnego wykonania Gradle w tym środowisku; wynik testów i kompilacji pozostaje NOT VERIFIED do zakończenia CI;
- najnowszy workflow run 42317859599 obejmuje SHA 93b99e1f6b6fed54695bb28e3c7368abd572a3d5 i ma status PENDING przy ostatnim odczycie;
- branch roboczy: 268 commits ahead, 0 behind; main nadal 752149d40186144b0f458a32b5826c8928349c3a;
- brak merge, APK, release i zmian main; Companion Transport pozostaje BLOCKED.


## Dodatkowa kontrola — checkpoint REQUESTING w Peer Review (2026-11-07)

Przegląd wszystkich trzech wywołań TeamRunEngine w MainActivity wykazał asymetrię: główna praca zespołu oraz recenzja patcha zapisywały trwały checkpoint REQUESTING przed wysłaniem żądania API, ale zwykły Peer Review nie przekazywał callbacku onRequestStarting. W razie przerwania procesu podczas recenzji mogło to pozostawić USAGE_PENDING bez kompletnych metadanych potrzebnych do bezpiecznego księgowania UNAVAILABLE. Mechanizm fail-closed chronił przed błędnym zerowym rozliczeniem, ale zadanie mogło pozostać nierozstrzygnięte.

Poprawka:
- dodano onRequestStarting do zwykłej ścieżki Peer Review;
- przed POST zapisywane są providerId, model, estymacje input/output, idempotencyKey oraz bezpieczny baseUrl (bez ścieżki URL);
- wynik Boolean zapisu checkpointu jest zwracany bezpośrednio do TeamRunEngine, więc brak potwierdzenia trwałego zapisu blokuje wysłanie żądania;
- nie zmieniano logiki peer-review, budżetu ani routingu.

Commit kodu: cd6fe6e301abf3e24f57326bd139976223b6378d.

Weryfikacja źródła po zapisie:
- MainActivity zawiera 3 callbacki onTaskResult i 3 callbacki onRequestStarting; liczby odpowiadają trzem ścieżkom raportowania i trzem ścieżkom zapisu metadanych REQUESTING.
- Testy i kompilacja tej poprawki oczekują na nowy run CI; nie oznaczono ich jako PASS.
- main, APK i release pozostają nietknięte; Companion Transport nadal BLOCKED.


## Dodatkowa walidacja — integralne znaczniki czasu archiwum raportów (2026-11-07)

Przegląd ścisłego parsera TaskResultArchive wykazał, że kontrola `createdAt is Number` dopuszczała także wartości zmiennoprzecinkowe. JSONObject.getLong() konwertuje taką wartość przez obcięcie części ułamkowej, co mogło zaakceptować uszkodzony rekord i zmienić jego znacznik czasu.

Poprawka:
- parser akceptuje teraz wyłącznie integralne typy JSON odczytane jako Int lub Long;
- wartości Double/Float (w tym ułamkowe znaczniki czasu) powodują odrzucenie całego archiwum;
- przy nieprawidłowym rekordzie put() nie nadpisuje istniejących danych;
- dodano test fractionalArchiveTimestampIsRejectedWithoutOverwritingExistingData.

Commity:
- implementacja: 921e81cd03b785ba1d42977589a59d39787380cd
- test: 05d0266cf3789bfe3bfd669266f221f028faa896

Weryfikacja CI dla najnowszej poprawki jest oczekiwana. Nie oznaczono testu jako PASS przed wykonaniem workflow.


## Etap 18 — walidacja parametrów KDF w zaszyfrowanej kopii dostawców (2026-11-07)

Audyt ścieżki importu zaszyfrowanej bazy modeli ujawnił, że BackupCrypto.decrypt() przyjmował liczbę iteracji PBKDF2 bez ograniczenia. Ponieważ wartość pochodzi z pliku wejściowego, spreparowana kopia mogła wymusić bardzo kosztowne wyprowadzenie klucza (DoS przez nadmierny work factor). Dopuszczalna była też arbitralna wartość mniejsza od parametru używanego przez aplikację.

Poprawka:
- dla kopii z polem `iterations` wymagane jest dokładne dopasowanie do stałej wersji 1: 210 000 iteracji PBKDF2-HMAC-SHA256;
- odrzucone są wartości ułamkowe, tekstowe, zaniżone i nadmiernie wysokie — zanim rozpocznie się kosztowne wyprowadzanie klucza;
- wyprowadzenie klucza używa wyłącznie stałej `ITERATIONS`, a nie bezpośrednio wartości z pliku;
- zachowano zgodność ze starszymi kopami v1 bez pola `iterations`: brak pola oznacza stały parametr 210 000;
- dodano testy odrzucania wartości 1 i 2 147 483 647 oraz test kompatybilności kopii v1 bez pola.

Commity:
- implementacja: f9866fb4cfa6371434d85f156edde185ae8d48af
- test KDF: f05b6f2dc665f0fcb4afb1295350f3b62db48133
- korekta literałów testu: b35e974a3200f26f9462e59d82073dadb6d96ac5, 2fffe3dec394860164e7a8ce1f283db040ad1ecf
- zachowanie zgodności wstecz: 3b6b8e38b9162a36d609f42c02365c4ffa9a3fdd
- test zgodności v1: c813442bca00ddc16d7839f426e7f5e60beec6ff
- korekta regex testu: bd2cf1df76b3de6a35995ab2aebbfa41bd8911f4

Kontrola:
- ponownie odczytano źródło BackupCrypto.kt i testy z brancha; testowe literały Kotlin/Regex sprawdzono po zapisie;
- nie wykonano jeszcze CI dla najnowszego zestawu zmian; testy pozostają NOT VERIFIED;
- poprzedni run 42318840461 nadal był IN_PROGRESS w ostatnim odczycie i może zostać anulowany przez nowszy commit;
- main pozostaje nietknięty; brak merge, APK i release; Companion Transport nadal BLOCKED.


## Etap 19 — atomowy import konfiguracji dostawców (2026-11-07)

Audyt SecureStore wykazał, że replaceAllConfigs() zapisywał każdy profil przez osobne saveConfig(). Jeśli zapis klucza API lub metadanych jednego z kolejnych profili nie powiódł się, wcześniejsze wpisy mogły już zostać nadpisane, mimo że cały import zwracał false. Podobny problem dotyczył saveConfig(): sekret i metadane były zatwierdzane w dwóch oddzielnych operacjach SharedPreferences.

Zmiana:
- dodano encryptSecret(), które przygotowuje zaszyfrowaną wartość bez mutowania SharedPreferences;
- saveConfig() szyfruje sekret przed zmianą stanu, a metadane, klucz i provider_ids zapisuje jednym Editor.commit();
- replaceAllConfigs() odrzuca pustą listę, puste ID i powtórzone ID przed szyfrowaniem lub mutacją;
- wszystkie sekrety i metadane są przygotowywane przed rozpoczęciem zapisu;
- zastąpienie konfiguracji, usunięcie starych wpisów oraz aktualizacja provider_ids odbywają się w jednym Editor.commit();
- błąd podczas przygotowania dowolnego wpisu pozostawia poprzednie preferencje bez zmian;
- usunięcie starych kluczy API jest częścią tej samej transakcji preferencji, a nie osobnymi operacjami.

Testy:
- SecureStoreImportTest.emptyReplacementIsRejectedWithoutChangingExistingConfig;
- SecureStoreImportTest.duplicateProviderIdsAreRejectedBeforeAnyMutation;
- testy sprawdzają, że odrzucony import nie zmienia istniejącego provider_ids, metadanych ani przechowywanej wartości klucza.

Pliki:
- app/src/main/java/pl/aimasterpro/app/data/SecureStore.kt
- app/src/test/java/pl/aimasterpro/app/data/SecureStoreImportTest.kt

Commity:
- a61c60a5c62da094d7b3211ed1622b76c0119f60 — atomowy zapis saveConfig i replaceAllConfigs;
- d7683eba52bf684c38d8b806ce5f33ebe03aa9cb — walidacja ID przed przygotowaniem sekretów;
- cc1215c2ae8ed517608f07298655c2cc9007c84a — testy regresyjne.

Weryfikacja:
- źródło i testy ponownie odczytane z brancha;
- CI run 42319363908 zakończył się jako CANCELLED po pojawieniu się nowszych commitów; nie jest to wynik testów;
- nowy run 42320893601 dla SHA cc1215c2ae8ed517608f07298655c2cc9007c84a ma status IN_PROGRESS; krok unit tests + assembleDebug jest w toku;
- porównanie branch/main: 285 commits ahead, 0 behind; main nadal 752149d40186144b0f458a32b5826c8928349c3a;
- brak merge, APK i release; Companion Transport pozostaje BLOCKED.


### Etap 19 — korekta formy metod i aktualizacja CI

Po przeglądzie statycznym zmieniono trzy metody SecureStore (putSecret, saveConfig, replaceAllConfigs) z expression body na block body, aby jawne wczesne wyjścia fail-closed były jednoznaczne i zgodne z ograniczeniami składni Kotlin. Zmiana nie modyfikuje logiki transakcji.

Commit: d35b6b99a66c7db3561fca541e7a16aa5600e5c0.

CI:
- run 42321007105 dla SHA d35b6b99a66c7db3561fca541e7a16aa5600e5c0: PENDING przy ostatnim odczycie;
- poprzedni run 42320893601 dla SHA cc1215c2ae8ed517608f07298655c2cc9007c84a: IN_PROGRESS przy ostatnim odczycie, może zostać zastąpiony przez nowszy run;
- żaden wynik testów ani assembleDebug nie jest jeszcze potwierdzony jako PASS.


### Etap 20 — walidacja schematu kopii i komunikatów importu (2026-11-07)

Przegląd MainActivity wykazał dwa problemy warstwy wywołującej:
- importer nowego formatu nie sprawdzał schemaVersion mimo zapisywania go podczas eksportu;
- komunikat błędu nadal sugerował częściową aktualizację, choć Etap 19 przeniósł wszystkie zapisy do jednego commit().

Zmiany:
- importer kopii weryfikuje teraz schemaVersion == 1 przed zmianą konfiguracji;
- oba importery (nowa kopia i migracja starego formatu) komunikują, że przy odrzuceniu importu istniejące konfiguracje pozostają bez zmian;
- zapis konfiguracji pozostaje za jednym transakcyjnym Editor.commit() z Etapu 19.

Commit: cd12622b4bc551c4362cf81d20f6aaa668d23c78.

CI:
- run 42321164179 dla SHA cd12622b4bc551c4362cf81d20f6aaa668d23c78: PENDING przy ostatnim odczycie;
- run 42321007105 dla SHA d35b6b99a66c7db3561fca541e7a16aa5600e5c0: IN_PROGRESS przy ostatnim odczycie; wcześniejsze runy dla poprzednich SHA są anulowane przez nowsze commity;
- brak potwierdzonego PASS testów i assembleDebug.


## Etap 21 — limity rozmiaru kopii i higiena materiału kryptograficznego (2026-11-07)

Audyt BackupCrypto.kt wykazał brak limitu rozmiaru wejściowego. Zaszyfrowana kopia mogła zostać sparsowana i poddana kosztownym operacjom bez wcześniejszego ograniczenia wielkości.

Zmiany:
- limit plaintextu przed szyfrowaniem: 4 MiB;
- limit koperty JSON przed parsowaniem: 6 MiB;
- limit długości ciphertextu weryfikowany przed KDF;
- walidacja długości hasła co najmniej 8 znaków również przy odszyfrowywaniu;
- wspólna funkcja deriveKey() czyści PBEKeySpec w bloku finally;
- bufor bajtowy klucza po utworzeniu SecretKeySpec jest zerowany;
- bufor odszyfrowanego plaintextu jest zerowany po utworzeniu String;
- testy limitów payload/envelope i zbyt krótkiego hasła.

Pliki:
- app/src/main/java/pl/aimasterpro/app/data/BackupCrypto.kt
- app/src/test/java/pl/aimasterpro/app/BackupCryptoTest.kt

Commity:
- 4e8a5a02e2f5cfb7760b63a3b27c6efa790b0218 — limity rozmiaru i higiena buforów kryptograficznych;
- b6df3f4a5492ae87aa8f3ed8f853f35b55725ca9 — testy regresyjne limitów.

CI:
- run 42321312810 dla SHA b6df3f4a5492ae87aa8f3ed8f853f35b55725ca9: PENDING przy ostatnim odczycie;
- run 42321164179 dla SHA cd12622b4bc551c4362cf81d20f6aaa668d23c78: IN_PROGRESS; wcześniejszy run dla 4e8a5a02 został anulowany przez nowszy commit;
- nie ma jeszcze potwierdzonego PASS dla testów ani assembleDebug.

Porównanie branch/main: 292 commits ahead, 0 behind. Main nadal 752149d40186144b0f458a32b5826c8928349c3a. Bez merge, APK i release. Companion Transport nadal BLOCKED.


## Etap 22 — ograniczenie odczytu kopii i walidacja profili dostawców (2026-11-07)

Audyt ścieżki importu ujawnił dodatkowy problem: MainActivity używał bufferedReader().readText() dla wybranego pliku kopii. Limit w BackupCrypto był sprawdzany dopiero po wczytaniu całego pliku do pamięci. Dodano strumieniowy odczyt z limitem 6 MiB, który przerywa czytanie po przekroczeniu limitu. Również dane przekazane bezpośrednio do importera są sprawdzane przed odszyfrowaniem.

Higiena hasła:
- hasło jest pobierane bezpośrednio do CharArray z pola wejściowego, bez tworzenia dodatkowego String przez toString();
- tymczasowa tablica jest zerowana w finally po szyfrowaniu/odszyfrowywaniu;
- pole hasła jest czyszczone po operacji;
- tablica jest tworzona dopiero po walidacji danych wejściowych i dostępności konfiguracji, aby wczesne wyjścia nie pozostawiały jej w pamięci.

Walidacja importu SecureStore:
- wymagane pola tekstowe: id, name, kind, baseUrl, model;
- dozwolone enumy kind: OPENAI_COMPATIBLE, GEMINI, ANTHROPIC;
- ograniczenia długości dla ID, nazwy, URL, modelu i klucza API;
- ID ograniczone do bezpiecznego zestawu znaków używanego w kluczach SharedPreferences;
- kontrola typów opcjonalnych pól enabled i parentProviderId;
- relacja parentProviderId musi wskazywać istniejący profil, nie może wskazywać na siebie ani tworzyć cyklu dziedziczenia;
- walidacja odbywa się przed szyfrowaniem sekretów i przed jakąkolwiek mutacją preferencji;
- zapis nadal pozostaje pojedynczym atomowym commit().

Commity:
- 0f6381e — walidacja schematu profili;
- 1ffe5ab — odczyt kopii z limitem i czyszczenie buforów hasła;
- decc47c — eliminacja bufora hasła na ścieżkach wczesnego wyjścia;
- aee26c9 — testy wadliwego typu pola i brakującego rodzica;
- 8a3657b — odrzucanie cykli dziedziczenia profili;
- f367874 — test regresyjny cyklicznych relacji.

CI:
- run 42321919632 dla SHA f3678746fa715272a972151c41033820f4772bc1: PENDING przy ostatnim odczycie;
- run 42321908552 dla SHA 8a3657bb8b04e9771e59d4a0eeb9277736d4dd08: anulowany przed uruchomieniem testów przez nowszy commit;
- brak potwierdzonego PASS testów jednostkowych i assembleDebug.

Weryfikacja kodu:
- schema importu bazuje na rzeczywistym ProviderConfig z core/Models.kt i na serializacji saveProvider(); puste baseUrl/model pozostają dozwolone, ponieważ aplikacja przechowuje niekompletne profile do późniejszego uzupełnienia;
- nie dodano walidacji składni URL, ponieważ puste i niestandardowe endpointy są prawidłowymi stanami edytowalnego profilu;
- pełna walidacja testami nadal oczekuje na CI.



## Etap 23 — czyszczenie sekretów SecureStore i ścieżki wyjątków (2026-11-07)

Kontynuacja przeglądu kryptografii wykazała, że SecureStore tworzył tymczasowe tablice bajtów zawierające klucz API i odszyfrowany sekret, ale nie zerował ich po użyciu. Dodatkowo odczyt sekretu dopuszczał bufor z IV i ciphertextem krótszy niż pełny tag GCM.

Zmiany:
- encryptSecret() zeruje tablicę plaintextu w finally;
- zerowane są tymczasowe połączone IV+ciphertext oraz tablica ciphertext po kodowaniu Base64;
- getSecret() wymaga co najmniej 28 bajtów (12 bajtów IV + minimum 16 bajtów tagu GCM);
- getSecret() zeruje tablice packed, IV, ciphertext i odszyfrowany plaintext również przy wyjątku;
- BackupCrypto.encrypt() zeruje plaintext bytes także wtedy, gdy doFinal() rzuci wyjątek;
- BackupCrypto.encrypt() zeruje ciphertext po zakodowaniu;
- BackupCrypto.decrypt() zeruje plaintext również wtedy, gdy walidacja po odszyfrowaniu lub utworzenie String zakończy się błędem;
- dodano test odrzucania zbyt krótkiego sekretu GCM oraz test niedozwolonych znaków identyfikatora profilu.

Commity:
- 0a28a85 — higiena buforów SecureStore i minimalna długość tagu GCM;
- 6cac03c — czyszczenie buforów BackupCrypto na ścieżkach wyjątków;
- ae93673 — testy regresyjne.

Uwagi:
- API klucze i hasła są nadal reprezentowane przez String w warstwie modelu/UI, więc nie można wyzerować ich oryginalnych obiektów String; zmiany ograniczają czas życia dodatkowych mutowalnych kopii bajtowych.
- Android Keystore nie udostępnia aplikacji możliwości ręcznego wyzerowania klucza sprzętowego; materiał klucza pozostaje zarządzany przez system.
- Nie usuwano ani nie regenerowano istniejącego aliasu Keystore przy błędzie odczytu, aby nie zniszczyć dostępu do już zapisanych sekretów.



Dodatkowa kontrola Etapu 23:
- wykryto, że saveConfig() nadal przyjmował identyfikator i pola profilu bez walidacji, mimo że import używał rygorystycznego schematu;
- saveConfig() korzysta teraz z tej samej walidacji profilu przed szyfrowaniem i przed zmianą SharedPreferences;
- dodano test odrzucenia wadliwego ID w pojedynczym zapisie bez naruszenia poprzednich danych.
Commits:
- 1266eac — walidacja profilu przy pojedynczym zapisie;
- d835e41 — test regresyjny.


## Etap 24 — fail-closed przy niedostępnych sekretach Android Keystore (2026-11-07)

Odkrycie:
- getSecret() zwracał null przy każdym błędzie odszyfrowania;
- readConfig() zamieniał taki wynik na pusty apiKey, nie odróżniając braku zapisanego klucza od istniejącego, ale nieczytelnego ciphertextu;
- zapis profilu z pustym kluczem mógł następnie usunąć istniejący ciphertext, mimo że aplikacja nie była w stanie go odszyfrować.

Zmiany:
- readConfig() rozróżnia brak klucza API od sytuacji, gdy zaszyfrowany wpis istnieje, ale odszyfrowanie się nie powiodło; w drugim przypadku zwraca jawny marker _secretUnavailable=true;
- unavailableSecretProviderIds() pozwala wykryć profile z niedostępnymi sekretami;
- MainActivity blokuje użycie profilu z niedostępnym sekretem oraz profilu zależnego od takiego rodzica;
- ekran MODELE pokazuje ostrzeżenie z ID profili, których sekretów nie można odszyfrować;
- saveConfig() odrzuca pusty zapis, jeżeli istniejący sekret nie daje się odszyfrować;
- putSecret() analogicznie blokuje usunięcie nieczytelnego ciphertextu pustą wartością;
- dodano testy markera niedostępnego sekretu, ochrony istniejącego ciphertextu przy saveConfig() i putSecret().

Commity:
- 27fb325 — fail-closed readConfig i diagnostyka niedostępnych sekretów;
- b0f894f — blokada użycia i ostrzeżenie UI;
- f790c03 — testy odczytu i zachowania sekretu;
- 23d34a6 — ochrona putSecret;
- b72c3d7 — test putSecret.

Ograniczenie:
- nie ma automatycznej regeneracji aliasu Android Keystore. Jeśli klucz jest trwale utracony, istniejący ciphertext może być nieodzyskiwalny; aplikacja nie niszczy go automatycznie ani nie deklaruje, że sekret odzyskano. Ponowne zapisanie wymaga świadomego dostarczenia nowego klucza i działającego Keystore.

CI:
- najnowszy kodowy commit b72c3d76d49a81c955ed22dc81bfd6e266dfd37a;
- testy jednostkowe i assembleDebug oczekują na CI; wcześniejszy run 42322341151 nie obejmuje tych commitów i został anulowany przez kolejne zmiany.



Dodatkowe zabezpieczenie Etapu 24 — brakujący alias Android Keystore:
- wykryto, że key() generował nowy alias, gdy stary alias nie był widoczny, nawet jeśli w SharedPreferences nadal znajdowały się ciphertexty kluczy API;
- to zachowanie mogło utrwalić sytuację, w której stare sekrety są nieczytelne dla nowego klucza;
- generowanie nowego aliasu jest teraz dozwolone wyłącznie wtedy, gdy nie istnieją zapisane zaszyfrowane sekrety;
- przy braku aliasu i istniejących ciphertextach SecureStore przerywa operację, zachowuje dane i zgłasza stan niedostępnego sekretu;
- dodano testy czystej polityki tworzenia klucza.

Commits:
- 557c470 — brak automatycznej regeneracji aliasu przy istniejących ciphertextach;
- 79da584 — jawna, testowalna polityka generowania klucza;
- 6012537 — testy polityki odzyskiwania.



Dalsze rozszerzenie Etapu 24 — import, eksport i ręczna naprawa:
- replaceAllConfigs() sprawdza wszystkie istniejące zaszyfrowane wpisy przed przygotowaniem importu; jeżeli choć jeden sekret jest nieczytelny, cały import jest odrzucany przed mutacją SharedPreferences;
- dodano helper hasUnreadableExistingSecrets() oraz testy: import nie usuwa nieczytelnego ciphertextu ani nie zmienia listy profili, testy pokrywają zbiór mieszany i pusty;
- metadane wewnętrzne _secretUnavailable są usuwane przed zapisaniem profilu i nie trafiają do importowanych metadanych;
- eksport bazy jest blokowany, jeśli choć jeden sekret nie daje się odszyfrować — aplikacja nie tworzy pozornie poprawnej kopii z pustym kluczem;
- przy prawidłowym eksporcie wewnętrzny marker diagnostyczny jest usuwany z eksportowanych profili;
- ekran MODELE pokazuje akcję NAPRAW KLUCZ dla profili z niedostępnym sekretem; dialog wymaga wprowadzenia nowego klucza, a zapis i dalsze przypisanie profilu są blokowane, jeśli zapis się nie powiedzie.

Dodatkowe commity Etapu 24:
- 517995f — blokada replaceAllConfigs przy istniejących nieczytelnych sekretach;
- d833be3 — testy ochrony importu pełnej konfiguracji;
- 9ce915d — usuwanie wewnętrznego markera z metadanych importu;
- 9e45375 — UI ręcznej naprawy profilu;
- 5820a5f — wymaganie nowego klucza w trybie naprawy;
- 4221e12 — blokada eksportu niekompletnej kopii.

Ważne ograniczenie naprawy:
- jeżeli stary alias Keystore nadal działa, ale pojedynczy ciphertext jest uszkodzony, ręczne wprowadzenie nowego klucza może zastąpić wyłącznie ten profil;
- jeżeli alias Keystore zniknął, aplikacja nie wygeneruje nowego klucza automatycznie, ponieważ zrobiłoby to pozostałe ciphertexty nieczytelnymi. W takim przypadku ręczna naprawa pojedynczego profilu zostanie bezpiecznie odrzucona; potrzebny jest osobny, jawnie zatwierdzany proces rotacji/resetu magazynu z pełnym ostrzeżeniem o utracie starych sekretów.


## Etap 25 — pełne czyszczenie buforów kopii zapasowej (2026-11-07)

Dalszy przegląd BackupCrypto wykazał ścieżki wyjątków, na których tablice tymczasowe nie były zerowane:
- encrypt() tworzył plaintext bytes, a limit rozmiaru sprawdzał przed wejściem do finally;
- błąd KDF lub inicjalizacji Cipher mógł wystąpić przed dotychczasowym czyszczeniem plaintextu;
- decrypt() nie czyścił salt, IV i ciphertextu, gdy walidacja długości, KDF lub GCM rzucały wyjątek;
- encoded key bytes były czyszczone dopiero po poprawnym utworzeniu SecretKeySpec.

Zmiany:
- cały przebieg encrypt() po utworzeniu plaintext bytes jest objęty finally;
- plaintext jest zerowany także po odrzuceniu limitu 4 MiB i błędzie KDF/Cipher;
- ciphertext jest zerowany niezależnie od powodzenia serializacji JSON;
- salt i IV są zerowane po zakodowaniu koperty;
- decrypt() zeruje salt, IV i ciphertext również przy błędzie walidacji, KDF i GCM;
- deriveKey() zeruje encoded bytes także wtedy, gdy konstruktor SecretKeySpec zakończy się wyjątkiem;
- PBEKeySpec nadal jest czyszczony w finally.

Commit:
- 3881ba1b0cb77a077c112909ccfdb9eed87b87a9 — pełne czyszczenie buforów BackupCrypto.

Testy istniejące obejmują round-trip, błędne hasło, tampering, zgodność formatu v1, limity payload/envelope oraz minimalną długość hasła. Nowe zmiany wymagają ponownego wyniku CI; nie oznaczono ich jako PASS.


Uzupełnienie Etapu 25:
- deriveKey() przenosi zerowanie encoded key bytes do finally, również gdy konstrukcja SecretKeySpec zawiedzie;
- dodano jawne sprawdzenie możliwości zastąpienia uszkodzonego sekretu: istniejący alias musi być dostępny i zawierać SecretKey;
- UI nie pokazuje akcji naprawy pojedynczego sekretu, gdy alias Keystore nie istnieje lub ma nieprawidłowy typ. Zamiast tego wyjaśnia, że wymagany byłby osobny, świadomy reset magazynu;
- dodano test polityki dopuszczania naprawy wyłącznie przy istniejącym, prawidłowym kluczu.

Dodatkowe commity:
- d7cd42e — bezpieczne wykrywanie dostępności aliasu;
- 908bd1b — test polityki odzyskiwania;
- e43a459 — UI nie oferuje mylącej naprawy przy braku aliasu.


Dodatkowa korekta zgodności starszych zapisów:
- stare wpisy SharedPreferences mogły zawierać pusty tekst w key_<id>; pusty wpis nie jest ciphertextem i powinien być traktowany jak brak klucza, nie jak awaria odszyfrowania;
- dodano hasStoredCiphertext(), które rozróżnia pusty wpis od niepustego ciphertextu; w razie błędnego typu wartości traktuje wpis konserwatywnie jako istniejący;
- odczyt profilu, diagnostyka, zapis pojedynczy, putSecret() i kontrola brakującego aliasu korzystają teraz ze spójnej definicji istniejącego ciphertextu;
- dodano test pustego historycznego wpisu klucza.

Commits:
- f290633 — spójne rozpoznawanie pustych i nieprawidłowych wpisów sekretów;
- 0af2423 — test zgodności pustego legacy wpisu.


Kontrola zgodności z formatem istniejącej instalacji:
- porównano SecureStore z gałęzi main (stan bazowy 0.6.54): nazwa SharedPreferences secure_provider_store, alias ai_master_provider_key_v1 oraz nazewnictwo config_<id>/key_<id> są zgodne;
- dotychczasowe profile i zaszyfrowane sekrety nie wymagają migracji nazwy magazynu ani aliasu;
- stare puste wpisy key_<id> są traktowane jak brak sekretu; niepuste, ale nieczytelne wpisy są chronione i nie są automatycznie usuwane;
- migracja kopii legacy pozostaje osobną ścieżką importLegacyBackup(), a replaceAllConfigs() teraz odrzuca import, jeśli zastąpiłby magazyn zawierający choć jeden nieczytelny sekret.


## Audyt uzupełniający po Etapie 25 — dekodowanie koperty i semantyka commit()

Kontrola kodu na branchu audit/0.6.137-routing-hardening:
- W BackupCrypto.decrypt() dekodowanie Base64 pól salt, iv i data nadal odbywa się przed chronionym try/finally. Jeżeli dekodowanie późniejszego pola rzuci wyjątek, wcześniej utworzone tablice nie zostaną wyzerowane.
- Plan korekty: zadeklarować nullable bufory przed try, dekodować wewnątrz try i wyzerować każdą utworzoną tablicę w finally. Dodać regresje dla błędnego Base64 w kolejnych polach; testy muszą potwierdzić odrzucenie koperty, a nie jedynie sam wyjątek z dekodera.

Nowe ustalenie dotyczące trwałości SecureStore:
- Android AOSP SharedPreferencesImpl.commit() najpierw wykonuje commitToMemory(), który modyfikuje mapę procesu, a dopiero potem synchronizuje zapis na dysk i zwraca wynik.
- W konsekwencji commit() == false nie oznacza rollbacku mapy w pamięci. saveConfig()/replaceAllConfigs() mogą zgłosić błąd zapisu, podczas gdy bieżący proces nadal widzi zmienioną mapę; po restarcie stan na dysku może być inny.
- Źródło: AOSP SharedPreferencesImpl, commitToMemory() i commit(): https://android.googlesource.com/platform/frameworks/base/%2B/master/core/java/android/app/SharedPreferencesImpl.java
- Dotychczasowe sformułowanie o atomowości importu należy rozumieć wyłącznie jako przygotowanie danych przed pojedynczym Editor.commit(); nie gwarantuje ono rollbacku pamięci ani transakcji obejmującej awarię dysku.
- Nie dodano jeszcze obejścia. Do rozważenia: trwały magazyn transakcyjny (Room/SQLite) lub jawny dziennik/wersjonowanie z procedurą recovery. Samo ponowne zapisanie starej mapy po false nie daje gwarancji, jeśli dysk nadal odrzuca zapis.
- Do czasu wdrożenia magazynu transakcyjnego należy traktować false z commit() jako stan niepewny: zablokować dalsze mutacje SecureStore w bieżącej sesji, pokazać komunikat wymagający ponownego uruchomienia/diagnostyki i nie raportować sukcesu.

Kontrola ścieżki naprawy profilu:
- providerDialog(recoverSecret=true) wymaga niepustego nowego klucza; saveProvider() zwraca wynik saveConfig(), a dalsze przypisanie profilu następuje dopiero po sukcesie.
- Dla profilu zależnego należy osobno przetestować semantykę parentProviderId: loadProvider() pobiera klucz i ustawienia dostawcy nadrzędnego, dlatego naprawa sekretu profilu potomnego nie może być przedstawiana jako naprawa sekretu rodzica.
- Testy integracyjne UI/Store powinny objąć naprawę profilu bazowego, potomnego oraz sytuację błędu commit().

Stan weryfikacji:
- CI run 42323653129, SHA 0af24234d852f1bf502879387823e1c06549b385, ostatni odczyt: in_progress; zasadniczy krok testów i assembleDebug nie zakończył się.
- Nie oznaczać testów ani kompilacji jako PASS.
- Niniejszy wpis jest dokumentacją audytu; workflow android-audit ignoruje zmiany *.md, więc ten checkpoint nie powinien uruchomić ani anulować testów.


## Dodatkowy przegląd integracji SecureStore z UI — 2026-11-07 02:55 CET

Potwierdzone na MainActivity.kt:
- saveProvider() propaguje wynik SecureStore.saveConfig(); dialog naprawy klucza wymaga nowej wartości i nie przypisuje profilu po błędzie zapisu.
- Import zaszyfrowanej bazy i import legacy wyświetlają komunikat „Dotychczasowe konfiguracje pozostały bez zmian” po false z replaceAllConfigs(). To stwierdzenie jest zbyt mocne w świetle semantyki SharedPreferences.commit(): false nie gwarantuje rollbacku mapy w pamięci ani stanu na dysku.
- seedDefaultProviders() i seedMissingDefaultProviders() odczytują listę profili, a następnie próbują dopisać brakujące profile. Po nieudanym commit() i potencjalnej rozbieżności pamięć/dysk nie mogą być traktowane jako wiarygodny stan magazynu.
- allConfigs()/readConfig() nie udostępniają obecnie stanu „storage uncertain”; UI nie ma sposobu odróżnienia pustego magazynu od magazynu, którego zapis zakończył się niepewnie.
- putSecret(), saveConfig() i replaceAllConfigs() nie mają wspólnej blokady mutacji po false z commit(). Wymagany jest jeden, współdzielony mechanizm awarii magazynu, nie tylko lokalne false.

Wniosek projektowy przed zmianą kodu:
1. Nie maskować stanu niepewnego jako pustej bazy ani jako potwierdzonego rollbacku.
2. Krótkoterminowa osłona musi blokować wszystkie dalsze zapisy w SecureStore po pierwszym nieudanym commit() w bieżącym procesie oraz jawnie informować UI, że stan wymaga ponownego uruchomienia i weryfikacji. Odczyty w tej sesji również nie mogą być przedstawiane jako wiarygodny snapshot.
3. Długoterminowo przenieść metadane i ciphertexty do SQLite z transakcją, zachowując SharedPreferences jako źródło migracji do czasu pełnego zapisu, odczytu kontrolnego i zatwierdzenia wersji migracji. Nie usuwać starego magazynu w tej samej fazie migracji.
4. Przed migracją dodać testy awarii/odtworzenia i jasno rozdzielić awarię przygotowania danych od awarii commit/dysku.

To jest ustalenie audytowe i plan; nie wprowadzono jeszcze zmian w kodzie SecureStore. Nie wolno twierdzić, że import pozostawia poprzednią bazę nietkniętą, jeśli commit() zwróci false.


## CI i wykonalność magazynu transakcyjnego

- Odczytano .github/workflows/android-audit.yml: job ma timeout-minutes: 45; komenda wykonuje kolejno :app:testDebugUnitTest oraz :app:assembleDebug. Workflow ignoruje *.md i ma concurrency cancel-in-progress=true dla tej samej gałęzi. Dlatego nie należy wykonywać pushy kodu przed zakończeniem bieżącego przebiegu.
- Na 2026-11-07 02:55 CET job 128253800019 nadal in_progress; krok „Run unit tests and compile debug variant” nie zakończył się. Nie ma podstaw do uznania testów lub assembleDebug za PASS.
- app/build.gradle.kts: minSdk 26, obecnie brak Room/DataStore/SQLite dependency; natywne android.database.sqlite.SQLiteOpenHelper/SQLiteDatabase są dostępne bez dodawania biblioteki. Dla pierwszej migracji można więc rozważyć natywne SQLite, jeśli zakres transakcji i testów będzie prostszy niż wprowadzanie Room.
- Migracja musi być idempotentna i crash-safe: pozostawić prefs nietknięte; utworzyć DB i tabelę wersji; w jednej transakcji wstawić komplet metadanych oraz istniejących ciphertextów; wykonać kontrolny odczyt wszystkich rekordów; dopiero potem zapisać trwały marker migracji. Stare prefs zachować co najmniej do kolejnego uruchomienia i potwierdzenia, że DB jest kompletna. Nie usuwać ani nie rotować aliasu Keystore w trakcie migracji.


## Pokrycie testami SecureStore — przegląd 2026-11-07

Odczytano app/src/test/java/pl/aimasterpro/app/data/SecureStoreImportTest.kt:
- testy obejmują walidację schematu, duplikaty ID, brakującego rodzica, cykl dziedziczenia, uszkodzony/nieczytelny ciphertext, brak aliasu oraz blokowanie importu, który usunąłby istniejące nieczytelne sekrety;
- nie ma testu wymuszającego SharedPreferences.Editor.commit() == false ani testu zachowania SecureStore po takim wyniku;
- obecne asercje „bez zmiany istniejącej konfiguracji” dotyczą odrzuceń na etapie walidacji/przygotowania przed commit(), nie awarii zapisu po commitToMemory(); nie dowodzą rollbacku przy błędzie dysku;
- testy SecureStore korzystają z Robolectric i rzeczywistego SharedPreferences, dlatego test błędu commit() wymaga wprowadzenia małego punktu wstrzykiwania abstrakcji zapisu lub wydzielenia polityki stanu magazynu. Nie należy testować fałszywego scenariusza przez zwykłe błędne dane, bo to inna ścieżka.

Doprecyzowanie projektu SQLite:
- tabela providers może przechowywać id jako PRIMARY KEY, metadata_json bez apiKey/_secretUnavailable oraz encrypted_secret jako nieprzezroczysty ciphertext Base64; relacje parentProviderId pozostają w JSON albo w osobnej kolumnie;
- wersja schematu i znacznik ukończenia migracji muszą być w tej samej transakcji SQLite co wszystkie rekordy. Nie opierać przełączenia źródła prawdy na osobnym SharedPreferences.commit(), który sam ma problem z niepewnym wynikiem;
- przy pierwszym uruchomieniu: jeżeli DB nie zawiera znacznika migracji, odczytać stare prefs, wykonać BEGIN/TRANSACTION, skopiować wszystkie provider_ids/config_*/key_* (ciphertext bez odszyfrowywania), zweryfikować liczbę i kompletność rekordów, zapisać schema_version + migration_complete w tej samej transakcji i dopiero commit;
- stare prefs pozostają nietknięte jako kopia odzyskiwania. Jeżeli DB ma ukończoną migrację, DB jest źródłem prawdy; jeżeli DB jest niekompletna/uszkodzona, nie nadpisywać jej automatycznie ze starych prefs i nie udawać pustej bazy — blokada z diagnostyką.
- Zachować alias ai_master_provider_key_v1 i format ciphertextów; migracja bazy nie wymaga odszyfrowywania ani ponownego szyfrowania kluczy API.


## Dodatkowe znalezisko UI — przypisanie modelu agentowi

- W MainActivity.kt, w ścieżce przypisywania modelu z katalogu darmowych modeli (funkcja obsługująca wybór modelSpec dla roli), wywołanie saveProvider(finalConfig) ignoruje zwróconą wartość Boolean.
- Niezależnie od wyniku zapisu kod natychmiast aktualizuje agentProviders i chatPrefs, przełącza widok i wyświetla „MODEL ZOSTAŁ PRZYPISANY” oraz „Konfiguracja została trwale zapisana”.
- Jeśli SecureStore.saveConfig() zwróci false (walidacja, Keystore, szyfrowanie lub commit), agent może otrzymać przypisanie do profilu, którego nowa konfiguracja nie została potwierdzona jako zapisana. Komunikat sukcesu jest fałszywy.
- Naprawa: warunek if (!saveProvider(finalConfig)) { return } przed jakąkolwiek mutacją agentProviders/chatPrefs i przed komunikatem sukcesu. Zachować istniejące przypisanie agenta bez zmian po niepowodzeniu. Dodać test/wyodrębnioną politykę, jeśli ścieżka UI jest testowalna.
- To znalezisko wzmacnia potrzebę wspólnego kontraktu zapisu SecureStore: każdy caller musi sprawdzać wynik, a żadna wtórna preferencja nie może być zapisana przed potwierdzeniem zapisu profilu.


## Naprawy wdrożone na gałęzi audytowej — 2026-11-07

- `BackupCrypto.decrypt()`: dekodowanie pól Base64 (`salt`, `iv`, `data`) przeniesiono do bloku `try/finally`. Każda tablica jest przypisywana do zmiennej nullable natychmiast po alokacji i czyszczona w finally, także gdy dekodowanie kolejnego pola rzuci wyjątek.
- `BackupCryptoTest.kt`: dodano dwa testy regresyjne, w których poprawnie zakodowane wcześniejsze pola poprzedzają niepoprawne Base64 pola IV lub ciphertext. Testy budują kopertę przez JSONObject, aby zagwarantować, że błąd pochodzi z dekodera Base64, a nie z manipulacji tekstem JSON.
- `MainActivity.kt`: przypisanie modelu do agenta przerywa operację, jeśli `saveProvider(finalConfig)` zwróci false. Nie zapisuje wtedy agentProviders/chatPrefs i nie pokazuje komunikatu sukcesu.
- `MainActivity.kt`: tworzenie profilu modelu również przerywa dalsze kroki i nie pokazuje sukcesu, gdy `saveProvider(profile)` zwróci false.
- Pozostałe wywołania seedDefaultProviders()/seedMissingDefaultProviders() nadal ignorują false, ale nie zapisują zależnych przypisań ani nie pokazują komunikatu sukcesu. Warto dodać zbiorczą diagnostykę inicjalizacji, lecz nie blokować startu aplikacji przez brak opcjonalnego profilu domyślnego.
- Nie zmieniono jeszcze SecureStore. Polityka po false z SharedPreferences.commit() nadal wymaga wydzielenia testowalnej warstwy stanu niepewnego; nie należy wprowadzać pozornego rollbacku.
- Zmiany kodu wymagają nowego CI. Nie uzyskano jeszcze potwierdzonego wyniku testów jednostkowych ani assembleDebug dla tych commitów. Nie oznaczać jako PASS przed odczytem końcowego workflow.
- Najnowsze commity kodu: `e408041f` (crypto), `9658d13f` (testy Base64), `fc31a7d8` (agent assignment), `9bdbe3ed` (model profile). Pomiędzy nimi znajduje się też korekta testów `e65237d4`; ostateczna wersja testów została następnie poprawiona w `9658d13f`.


## SecureStore — blokada po nieudanym commit() — 2026-11-07

- Dodano procesowy, współdzielony latch `SecureStoreHealth` z blokadą wspólną dla wszystkich instancji `SecureStore`. Po nieudanym `SharedPreferences.Editor.commit()` (wynik false lub wyjątek) magazyn przechodzi w stan niepewny.
- Wszystkie zapisy `putSecret()`, `saveConfig()`, `replaceAllConfigs()` przechodzą przez jeden helper `commitOrMarkUncertain()`. Nieudany commit ustawia latch.
- Odczyty `getSecret()`, `readConfig()`, `allConfigs()`, `unavailableSecretProviderIds()` i operacje odzyskiwania również respektują stan niepewny. Po jego ustawieniu nie zwracają danych z potencjalnie rozjechanej mapy pamięciowej i nie wykonują dalszych zapisów.
- Wspólna blokada synchronizuje operacje między różnymi instancjami `SecureStore`, nie tylko wewnątrz pojedynczego obiektu. Latch jest celowo nieodwracalny w normalnym działaniu procesu; restart aplikacji odtwarza stan SharedPreferences z trwałego magazynu i czyści stan wyłącznie przez nowy proces.
- Ekran MODELE pokazuje ostrzeżenie i kończy renderowanie katalogu, gdy magazyn jest niepewny. Użytkownik otrzymuje instrukcję pełnego zamknięcia i ponownego uruchomienia aplikacji; nie sugerujemy rollbacku.
- Dodano test Robolectric sprawdzający współdzielenie latcha przez dwie instancje oraz blokowanie odczytów/zapisów i brak kolejnych mutacji prefs po ustawieniu stanu niepewnego.
- Ograniczenie testu: obecny test weryfikuje politykę blokady po ustawieniu latcha; nie symuluje fizycznego błędu I/O w `SharedPreferences.commit()`. Punkt commit jest scentralizowany, więc osobny test wymuszający false/wyjątek wymaga wstrzyknięcia małej abstrakcji edytora/zapisu. Nie oznaczamy tego scenariusza jako bezpośrednio przetestowanego.
- Długoterminowa migracja do SQLite nadal pozostaje osobnym zadaniem. Nie zmieniono formatu ciphertextów, aliasu Android Keystore ani danych zapisanych na urządzeniu.
- Commit kodu: `e2983da8`. CI dla tego SHA należy odczytać po zakończeniu; poprzedni run mógł zostać anulowany przez concurrency po pushu. Brak potwierdzonego PASS na tym etapie.


### Uzupełnienie testu awarii commit — 2026-11-07

- Konstruktor `SecureStore` otrzymał wewnętrzny punkt wstrzykiwania funkcji zatwierdzającej edytor. Domyślna ścieżka produkcyjna nadal wywołuje rzeczywiste `Editor.commit()`; testy mogą kontrolowanie zasymulować jego wynik.
- Dodano `failedPreferencesCommitPoisonsAllStoreInstances()`: wstrzyknięta funkcja wywołuje `Editor.apply()` (natychmiast aktualizuje mapę pamięciową SharedPreferences), po czym zwraca false, symulując sytuację, w której proces widzi zmiany mimo niepotwierdzonego zapisu trwałego. Test potwierdza ustawienie blokady globalnej, blokadę drugiej instancji oraz brak dalszych mutacji.
- Dodatkowy test zachowuje kontrolę nad resetem latcha wyłącznie w sekcji finally. W produkcji nie ma ścieżki ręcznego odblokowania; wymagany jest restart procesu.
- Commit testu: `21aad12b`; ograniczenie punktu wstrzykiwania do konstruktora internal i uporządkowanie odczytów: `8ffd38ad`.
- CI run dla `21aad12b` mógł zostać anulowany przez kolejny push w ramach concurrency. Ostateczny CI należy przypisać do najnowszego SHA kodu `8ffd38ad`, a nie do wcześniejszego runu.


### Dodatkowe odcięcie ścieżek użycia po awarii magazynu — 2026-11-07

- Audyt wywołań `SecureStore` w `MainActivity` wykazał, że samo zablokowanie metod magazynu i pokazanie ostrzeżenia w MODELE nie wystarcza: `chatWithFallback()` otrzymuje już zbudowany obiekt profilu i mógłby próbować wysłać żądanie, mimo że `allConfigs()` zwraca pustą listę po awarii.
- Dodano twardy warunek na wejściu do `chatWithFallback()`: stan niepewny kończy ścieżkę przed przygotowaniem fallbacków i przed wysłaniem żądania do dostawcy.
- `showModelPicker()` wykrywa ten stan i kieruje użytkownika do MODELE, gdzie znajduje się instrukcja restartu zamiast prezentować pustą listę profili jako normalny stan.
- Weryfikacja statyczna potwierdziła oba guardy w aktualnym `MainActivity.kt`.
- Commit: `23c3b590`.
- CI uruchomione dla SHA `23c3b590`, run `42328456275`; status przy zapisie checkpointu: pending. Starszy run `42328153066` dla `8ffd38ad` nadal był in_progress, ale nowszy push może spowodować anulowanie przez concurrency. Nie ma jeszcze potwierdzonego PASS.


### Ochrona pozostałych wejść do funkcji profili — 2026-11-07

- Kolejny przegląd call-site'ów wykazał, że ścieżka kopii zapasowej mogła wejść do formularza eksportu/importu przy niepewnym stanie i pokazać mylący komunikat o pustej bazie. Dodano wczesną blokadę obu operacji i skierowanie do ekranu MODELE.
- `saveProvider()` rozróżnia zwykły błąd zapisu od globalnego stanu niepewnego i podaje właściwą instrukcję restartu.
- Wcześniej dodane guardy chat/fallback i selektora modeli pozostają aktywne. Główne wejścia do użycia profili nie powinny traktować pustej listy zwróconej przez fail-closed `SecureStore` jako prawidłowej konfiguracji.
- Commit kodu: `739a7658`.
- CI dla wcześniejszego kodu `23c3b590` jest osobnym uruchomieniem i nie waliduje tego dodatkowego commita. Oczekiwany jest run dla najnowszego kodu; nie przypisujemy mu wyniku przed zakończeniem.


### Walidacja po rozszerzeniu guardów

- Aktualny kod aplikacji do walidacji: `739a7658abbeee7b280d5fc218fcffb6a665049b`.
- CI run: `42328556129`; job `128269544334`; krok `Run unit tests and compile debug variant` jest w toku. Konfiguracja JDK 17, Android SDK/API 36 i Gradle 8.13 zakończyła się powodzeniem.
- Commit dokumentacji checkpointu po zmianach: `6ce958af`; nie zmienia kodu aplikacji.
- Stan porównania z `main`: 355 commitów ahead, 0 behind. Brak merge i brak zmian w `main`.


### Regresja: wyjątek podczas commit oraz granice migracji

- Dodano test `thrownPreferencesCommitPoisonsAllStoreInstances()`: wstrzyknięty zapis aktualizuje mapę in-memory przez `apply()`, po czym rzuca wyjątek. Test sprawdza globalną blokadę i brak kolejnych zapisów z drugiej instancji.
- Commit testu: `fedc346d`.
- CI dla SHA `fedc346d`: run `42328857853`; status ostatniego odczytu: `in_progress`. Starsze runy zostały anulowane przez nowsze push'e; ich anulowanie nie jest wynikiem testów.
- Przegląd architektury wykazał, że aplikacja ma wiele niezależnych magazynów SharedPreferences (m.in. checkpointy, historia czatów, pamięć AI, rejestr tokenów). Planowana migracja SQLite w tym etapie dotyczy wyłącznie `secure_provider_store` i nie może automatycznie obejmować pozostałych magazynów.
- Wymagania przed implementacją migracji SecureStore: wersjonowany schemat i migracja transakcyjna; unikalne ograniczenia ID; metadane profilu i zaszyfrowany sekret w jednej transakcji; zachowanie Android Keystore bez eksportowania klucza; marker migracji ustawiany dopiero po odczycie kontrolnym; stary SharedPreferences pozostaje nietknięty aż do potwierdzenia nowego DB; idempotentne wznowienie po przerwaniu procesu; testy crash-pointów przed/po commit i po markerze; ścieżka rollback bez usuwania starego źródła.
- Nie rozpoczęto migracji ani zmiany formatu danych. To etap projektowania, nie wdrożenie.


### Specyfikacja migracji SQLite — dokument projektowy

- Dodano `SECURESTORE_SQLITE_MIGRATION_DESIGN_PL.md`, commit `41bf664c`.
- Dokument określa schemat DB, zachowanie formatu ciphertext i aliasu Keystore, transakcyjną migrację z legacy SharedPreferences, atomowy marker, punkty odzyskiwania po awarii, operacje po migracji i wymagany zestaw testów.
- To wyłącznie specyfikacja. Nie dodano SQLiteOpenHelper, nie zmieniono źródła prawdy i nie ruszono istniejących danych.


### Doprecyzowanie położenia dokumentu migracyjnego

- Dokument `SECURESTORE_SQLITE_MIGRATION_DESIGN_PL.md` znajduje się na gałęzi audytowej; commit branch: `c77f7386`.
- Korekta operacyjna: pierwsze wywołanie utworzenia tego pliku pominęło parametr gałęzi i skierowało commit `41bf664c` na domyślne `main`. Natychmiast przywrócono wskaźnik `main` do dokładnego poprzedniego SHA `752149d40186144b0f458a32b5826c8928349c3a` z kontrolą oczekiwanego SHA `41bf664c`. Weryfikacja po korekcie: `main` ponownie wskazuje `752149d`; porównanie audytowej gałęzi z `main` pokazuje 360 ahead / 0 behind. Nie pozostała zmiana zawartości na `main`.
- Dalsze operacje plikowe muszą jawnie przekazywać `branch: audit/0.6.137-routing-hardening`.


### Pełne odcięcie testów i discovery modeli po awarii SecureStore

- Dalszy audyt wszystkich wejść sieciowych wykazał, że poza czatem istniały osobne ścieżki: ręczny test modelu/dostawcy, discovery pojedynczego providera i odświeżanie wszystkich katalogów. Mogły korzystać z obiektu profilu utworzonego przed awarią magazynu.
- Dodano guardy:
  - `runBudgetedModelTest()` zwraca błąd przed rezerwacją budżetu i przed HTTP;
  - `discover()` i `refreshAllModelCatalogs()` blokują pobieranie katalogów;
  - `showAgents()` kieruje do ekranu MODELE zamiast prezentować pustą listę jako prawidłowy stan.
- Statyczna weryfikacja potwierdziła obecność czterech guardów. Commit: `e0400ed2`.
- CI dla wcześniejszego SHA `fedc346d` nie waliduje tego commita; oczekujemy nowego runu. Nie przypisujemy PASS bez zakończonego joba.
- Dokument projektu SQLite znajduje się na tej gałęzi w `SECURESTORE_SQLITE_MIGRATION_DESIGN_PL.md` (commit `c77f7386`). `main` zweryfikowany na SHA `752149d`, porównanie: 360 ahead / 0 behind przed najnowszym commitem kodu.


- Doprecyzowano projekt SQLite (commit `f2265427`): tabela zachowuje pełny `config_json` po usunięciu wyłącznie `apiKey` i wewnętrznego `_secretUnavailable`, a relację rodzica przechowuje dodatkowo w kolumnie FK. To zachowuje nieznane/rozszerzone pola JSON, które obecny importer toleruje i przechowuje; prosty schemat z samymi znanymi kolumnami mógłby je utracić.


### Dodatkowe asynchroniczne ścieżki API — 2026-11-07

- Audyt pełnego pliku `MainActivity.kt` ujawnił kolejne wejścia sieciowe, które miały guard tylko przed uruchomieniem workera lub nie miały go wcale:
  - automatyczna synchronizacja katalogu modeli w ekranie darmowych modeli;
  - synchronizacja wszystkich katalogów dostawców;
  - ręczne discovery pojedynczego dostawcy;
  - test modelu mógł przejść od rezerwacji budżetu do HTTP po zmianie stanu magazynu.
- Dodano ponowne sprawdzenie `SecureStoreHealth` wewnątrz workerów, bezpośrednio przed HTTP oraz przed zapisem pobranych katalogów. W razie blokady wynik synchronizacji jest odrzucany, a przycisk pozostaje wyłączony.
- `runBudgetedModelTest()` sprawdza stan ponownie po rezerwacji budżetu; w razie awarii żądanie nie jest wysyłane, a rezerwacja zostaje zwolniona przez istniejący `finally`.
- Commity kodu: `e83c171c` (automatyczny katalog) i `9a340a40` (ponowne kontrole w workerach).
- CI dla najnowszego SHA `9a340a40d16743df757fc2c0f8d70d575fa3e055`: run `42329774728`, status `pending` w chwili zapisu checkpointu. Poprzednie runy anulowano przez nowsze push'e; anulowanie nie oznacza ani PASS, ani FAIL.
- Porównanie gałęzi: 367 ahead / 0 behind. `main` pozostaje nietknięty.


### Ochrona bezpośrednich wywołań TeamRunEngine — 2026-11-07

- Przegląd wszystkich użyć `TeamRunEngine.runBatch()` ujawnił trzy bezpośrednie ścieżki HTTP, które omijały `chatWithFallback()`: główna współpraca agentów, peer review oraz peer review patcha.
- Wszystkie trzy callbacki `onRequestStarting` sprawdzają teraz `store.isStorageUncertain()` i zwracają `false` przed zapisem checkpointu `REQUESTING`. TeamRunEngine już egzekwuje kontrakt: gdy callback nie potwierdzi trwałego checkpointu, nie wykonuje POST.
- Istniejący test `requestCheckpointIsDurableBeforeHttpAndBlocksSendWhenPersistenceFails()` w `TeamRunEngineNetworkTest` potwierdza, że `onRequestStarting=false` blokuje HTTP; test nie wymaga dodatkowego serwera ani nowych zależności.
- Commity: `22f2e397` (guardy trzech ścieżek agentów) oraz `c18c37c5` (odrzucenie odpowiedzi katalogu po wykryciu awarii SecureStore).
- Dodatkowo automatyczna synchronizacja katalogu sprawdza stan po każdym HTTP i przed zapisem katalogu, aby nie utrwalać odpowiedzi, jeśli magazyn został w międzyczasie zablokowany.
- CI dla SHA `22f2e397`: run `42329952558`, w toku przy ostatnim odczycie. Najnowszy commit kodu `c18c37c5` ma run `42330058130`, status `pending`; to on będzie miarodajny dla aktualnego kodu.
- Stan gałęzi po tych zmianach: 370 ahead / 0 behind. `main` bez zmian.


- Dodatkowy przegląd migracji potwierdził `android:allowBackup="false"` w manifeście. W projekcie SQLite dopisano jawne `PRAGMA foreign_keys=ON`, kontrolę zgodności kolumn z JSON oraz `PRAGMA quick_check` i `PRAGMA foreign_key_check` po ponownym otwarciu bazy. Commit dokumentacji: `ae2fb547`. To nadal wyłącznie specyfikacja, bez zmiany kodu magazynu.


### Anulowanie aktywnych żądań po awarii zapisu — 2026-11-07

- Dodatkowo wdrożono reakcję na sam moment wykrycia niepewnego stanu: `saveProvider()` oraz ścieżki importu wywołują teraz `ProviderClient.cancelActiveRequests()` po nieudanym commit, jeśli `SecureStoreHealth` jest zablokowany.
- To unieważnia aktywne request scopes i rozłącza połączenia HTTP, zamiast tylko blokować kolejne zadania. Dotychczasowa semantyka anulowania POST pozostaje: jeśli dostawca mógł już przetworzyć żądanie, rozliczenie oznaczane jest jako nieznane i nie uruchamia się fallback.
- Zwykły błąd walidacji/importu, który nie ustawił latcha, nie anuluje żądań.
- Commit kodu: `3c6fcb4b`.
- CI dla SHA `3c6fcb4b07d7d04381451c0f041553f508588b91`: run `42330303816`, status `pending` przy ostatnim odczycie. Starszy run `42330058130` dotyczy poprzedniego SHA `c18c37c5`, nie najnowszego kodu.

### Domknięcie testu bramki żądania i korekta checkpointu

- Rozszerzono `requestCheckpointIsDurableBeforeHttpAndBlocksSendWhenPersistenceFails()` o drugi serwer fallback. Test potwierdza, że odrzucenie `onRequestStarting` blokuje także POST do każdego fallback providera, nie tylko do providera głównego. Commit testu: `7a6b24ad`.
- Uporządkowano formatowanie trzech guardów `onRequestStarting` (commit `85922717`).
- W peer review patcha znaleziono i poprawiono osobny błąd: URL providera był zapisywany z dosłownymi znakami ucieczki zamiast interpolowanej wartości hosta/protokołu. Commit: `e826de09`.
- Najnowszy kod do walidacji: `7a6b24ad6050cf5e56704375279732858d0b60da`; CI run `42330603571` ma status `pending` przy ostatnim odczycie. Runy wcześniejszych SHA anulowano przez nowsze push'e.

### Obsługa błędów odczytu SharedPreferences — 2026-11-07

- Wcześniejszy latch obejmował nieudany commit, ale odczyty `provider_ids`, `config_<id>` i `key_<id>` nie miały jednolitej obsługi wyjątków. Część ścieżek mogła zwrócić pustą lub częściową listę albo kontynuować zapis po błędzie odczytu.
- Dodano wspólne, defensywne odczyty identyfikatorów i ciphertextów. Wyjątek z API SharedPreferences ustawia ten sam procesowy latch; operacja zapisu sprawdza latch przed mutacją, a `allConfigs()` i `unavailableSecretProviderIds()` odrzucają wyniki częściowe.
- `getSecret()` odróżnia błąd odczytu z SharedPreferences (latch) od błędu dekodowania/deszyfrowania ciphertextu (sekret pozostaje oznaczony jako niedostępny, bez automatycznego usuwania).
- Dodano testy Robolectric z proxy SharedPreferences symulującym wyjątki: awaria drugiego odczytu konfiguracji odrzuca całą częściową listę; awaria odczytu `provider_ids` blokuje zapis przed commitem.
- Commity: `115657d8` (odczyty fail-closed), `ba6c6dca` (odrzucanie częściowych wyników), `5779f298` (testy błędów odczytu).
- CI dla `ba6c6dca` i `5779f298` uruchamia się osobno po kolejnych pushach; status końcowy musi być potwierdzony na najnowszym SHA. Nie traktować anulowanych runów jako PASS/FAIL.

- Uzupełniono testy o awarię odczytu zaszyfrowanego pola `key_<id>`: sekret pozostaje w oryginalnym SharedPreferences, latch blokuje dalsze operacje, a próba wyczyszczenia pustą wartością nie może go usunąć. Commit: `61e23cf7`.

- Doprecyzowano komentarz `SecureStoreHealth`: latch dotyczy zarówno nieudanego commitu, jak i wyjątku uniemożliwiającego wiarygodny odczyt; jedynym sposobem wyczyszczenia produkcyjnego latcha pozostaje restart procesu. Commit: `c787f228`.
- Aktualny kod do CI: `c787f22841be0a5de451b3fdbdf4a4fe8463e2e9`, run `42331335074` w statusie `pending` przy aktualizacji checkpointu. Starszy run `42331107485` dotyczy wcześniejszego SHA `115657d8` i nie waliduje najnowszych testów.


## Checkpoint wznowienia — 0.6.139 (2026-11-07)

- Aktywna robocza gałąź: `work/0.6.139-saveconfig-secret-preservation`, HEAD `7ee0908d8f7fbefe603493b48f154e5752e3f951`.
- Baza: `30e0f496a4383d935563a35e7b61b1742f853c71`.
- Zmiana produkcyjna: commit `d3fd3060b797c2ca127bb2d97395a19f253aab6c`; zachowanie istniejącego ciphertextu, gdy `apiKey` jest pusty i sekret jest czytelny.
- Testy: commit `7ee0908d8f7fbefe603493b48f154e5752e3f951`; cztery testy dodane, bez uruchomienia.
- CI run `42411603439` / job `128520747624`, SHA `30e0f496...`: nadal `in_progress`; kroki checkout/JDK/SDK/API36/Gradle zakończone, główny krok test+debug compile w toku. Limit 45 min; nie wysyłać commita na branch audytowy przed rozstrzygnięciem, bo concurrency może anulować run.
- Brak workflow run dla gałęzi roboczej; workflow push jest skonfigurowany dla gałęzi audytowej, a pull_request tylko dla `main`.
- Następny ruch: poczekać na wynik runu; następnie przenieść 0.6.139 do gałęzi audytowej, uruchomić CI na nowym SHA, pobrać raporty i rozstrzygnąć każdy błąd. Nie oznaczać testów jako PASS wcześniej.
- APK NOT BUILT; Companion NOT ACTIVATED; `main` untouched.


## Uzupełnienie checkpointu — historia rozmów

- Cztery testowe zmiany zapisano na work/0.6.139-saveconfig-secret-preservation: 9700c65, a2966d6, 401f40d, 36a907e.
- Pokrycie: commit false przed apply (stary snapshot i kwarantanna), apply przed błędem (nowy snapshot i restart recovery), wyjątek callbacka update bez zapisu, niejednoznaczny commit updateRoot i odtworzenie.
- Testy ADDED / NOT RUN. Aktywny CI 42411603439 nadal jest na SHA 30e0f496... i nie waliduje tych zmian ani 0.6.139.
- Statyczny audyt call sites: mutacje historii w MainActivity są synchronizowane; na razie brak dowodu na rzeczywisty równoległy zapis wymagający zmiany produkcyjnej.

### Checkpoint 0.6.139 — legacy mirror resurrection fix

- Potwierdzony scenariusz: usunięcie ostatniego czatu pozostawia istniejący `chats_v2` z pustym `chats`; stary `chat_messages` mirror może zawierać poprzednie wiadomości. Poprzednia logika `restoreMessages()` sprawdzała tylko `chats.length() == 0`, więc mogła błędnie zaimportować stary mirror.
- Naprawa: `ChatHistoryStore.shouldImportLegacyMirror()` rozróżnia brak kanonicznego pliku preferencji od istniejącego pustego dokumentu; wyjątek/kwarantanna daje null. `MainActivity.restoreMessages()` zatrzymuje odtwarzanie przy null i importuje mirror wyłącznie przy rzeczywistym braku `chats_v2`.
- Commity: `4f5cc77994fa05ac9e7747e5f469182d9ddb977a`, `42bf4479e77ff3b73cf93a025fd0c055406e55de`, testy `e7d1ae88f6c160f8c7743f3e754efe23241baa57`, korekta testu kwarantanny `4baec9da3c72bcdf89a68431470cfec5749799e7`.
- Testy ADDED / NOT RUN. CI 42411603439 nadal in_progress dla SHA 30e0f496...; nie obejmuje żadnego commita 0.6.139. Gałąź audytowa nie została dotknięta.

### Checkpoint 0.6.139 — atomowość MainActivity vs archiwa

- ChatResponseArchive.restoreArchivedResponses() i TaskResultArchive.restoreArchivedReports() używają ChatHistoryStore.updateRoot(). Wcześniejsze readRoot()+writeRoot() w MainActivity mogło zapisać przestarzały snapshot po aktualizacji archiwum.
- Przeniesiono 7 mutujących ścieżek MainActivity do updateRoot(): rename, delete, persistMessages, persistAgentActivity, saveCurrentId, appendToStoredChat i appendAgentActivityForChat. Bezpośrednie ChatHistoryStore.writeRoot() w MainActivity: brak po zmianie.
- Produkcja: `49da9dcb2a9de7296301476b90c65301c000fc0e`, `84c495ec0028394b6a1dab14b1599c31211c2f0e`; test współbieżności: `b4193751f496aa6d53846852deee1b77a969ad5f`.
- Test ADDED / NOT RUN. Nie ma jeszcze build/test wyniku dla tych commitów; CI 42411603439 nadal in_progress dla SHA 30e0f496...; branch audytowy bez zmian.

### Checkpoint 0.6.139 — zapis historii wspólnym atomowym API

- Wszystkie mutujące call sites MainActivity korzystają obecnie z `ChatHistoryStore.updateRoot()`: renameChat, deleteChat, persistMessages, persistAgentActivity, saveCurrentId, appendToStoredChat, appendAgentActivityForChat.
- Statyczna kontrola najnowszego MainActivity: 7 × updateRoot, 0 × writeRoot. Archiwa odpowiedzi i raportów już używały updateRoot, więc wszystkie te ścieżki są serializowane wspólnym mechanizmem.
- Commit refaktoryzacji: `49da9dcb2a9de7296301476b90c65301c000fc0e`; korekta no-op dla nieistniejącego czatu: `84c495ec0028394b6a1dab14b1599c31211c2f0e`; test współbieżności: `b4193751f496aa6d53846852deee1b77a969ad5f`.
- Branch roboczy HEAD po checkpointach: `f614a5f5c2369824a75f99b7f780826e793b44c0`. CI 42411603439 nadal dotyczy SHA 30e0f496...; brak potwierdzonego wyniku test/build dla aktualnego work branch.

### Checkpoint 0.6.139 — replay archiwów przy niejednoznacznym commit historii

- Dodano dwa warianty awarii dla ChatResponseArchive: commit przed apply i po apply; po symulowanym restarcie test weryfikuje odzyskanie albo idempotentny brak duplikacji.
- Dodano analogiczne dwa warianty dla TaskResultArchive; archiwum ma pozostać dostępne, gdy zapis do chats_v2 jest niejednoznaczny.
- Testy: `4ef5bc95e44c57ba7b12635fe67c13365b1dc5e8`, `d2fc3f5518fc60956bbda42403884289793d6a7d`; fixture `c08164e06135fb0d0815db4145591a544a254e61`.
- Status: ADDED / NOT RUN. CI 42411603439 nadal in_progress dla SHA 30e0f496...; nie waliduje work branch.

### Checkpoint 0.6.139 — niejednoznaczny commit archiwum odpowiedzi

- Dodano test ambiguousArchiveCommitQuarantinesRecoveryReadsAndWrites dla ChatResponseArchive: commit zwraca false po symulowanym apply; wymagane jest oznaczenie archiwum jako uncertain, blokada odczytów i kolejnych zapisów.
- Commit: e08735883591841b645202f33e72d307f809f1b5.
- ADDED / NOT RUN. CI 42411603439 nadal in_progress dla SHA 30e0f496...; nowy test nie jest jeszcze zweryfikowany.

### Checkpoint 0.6.139 — kwarantanna archiwów blokuje replay

- Rozszerzono testy awarii commit archiwum odpowiedzi MASTER i raportów agentów o bezpośrednie wywołanie metod restore; wymagane jest zgłoszenie błędu, gdy archiwum jest niepewne.
- Commity: `5eb52b62daf2fa1ae1cff91bcfac2abcbad53769`, `5fbfa1269fba52deb88ff8d1938339998bec2129`.
- ADDED / NOT RUN; CI 42411603439 nadal in_progress dla wcześniejszego SHA `30e0f496...`.

### Checkpoint 0.6.139 — ChatResponseArchive: awaria dostępu i blokada

- Test `preferenceAccessFailureDoesNotPretendResponseArchiveIsEmptyOrAcceptNewAnswer` pokrywa niedostępność SharedPreferences.
- Test `archivePutWaitsForSharedPreferenceLockAcrossReadModifyWrite` sprawdza serializację pełnej operacji zapisu.
- Commit `bccb390d38f4ecb42ffb64fcf60fe128a4ddb8bd`; ADDED / NOT RUN.
- CI 42411603439 nadal in_progress na wcześniejszym SHA `30e0f496...`.

### Checkpoint 0.6.139 — poprawka błędu dostępu archiwów

- Naprawiono rzeczywistą niespójność kontraktu `put()`: awaria `withHealthyAccess()` nie może wydostać się jako wyjątek; oba archiwa odpowiedzi i raportów zwracają `false`.
- Testy istnieją: `preferenceAccessFailureDoesNotPretendResponseArchiveIsEmptyOrAcceptNewAnswer` oraz `preferenceAccessFailureDoesNotPretendArchiveIsEmptyOrAcceptNewReport`; test blokady transakcji read-modify-write dla obu archiwów.
- Commity: `d8223a7b90d9a27c117ca98a1943002160186577`, `9692a908422a227f1078133837ea5aa0e03277ab`, `bccb390d38f4ecb42ffb64fcf60fe128a4ddb8bd`.
- Testy nadal ADDED / NOT RUN; nie ogłaszać zaliczenia bez wyniku CI.

- Testy blokady `ChatResponseArchive` i `TaskResultArchive` wzmocniono do asercji stanu `Thread.State.BLOCKED` na wspólnej blokadzie; commity `b14f6ff8d48ff125b8c1bcc274889ecf15f1896b` oraz `808555f1fabbd45e51cd9f757b812a0601adc72e`. ADDED / NOT RUN.

### Checkpoint 0.6.139 — obsługa startowego recovery

- Awaria `TaskResultArchive.restoreArchivedReports()` jest teraz logowana na `AIMasterRecovery`; startup kontynuuje bez fałszywego komunikatu o odzyskaniu.
- `chatTitles` aktualizuje się dopiero po udanym zapisie kanonicznego `chats_v2`.
- Commit `ab954ce9e099afeecf6adfc68c251229d3c2e332`; statyczny przegląd kodu, bez uruchomionego testu/builda.

### Checkpoint 0.6.139 — transakcyjność bezpośredniego writeRoot

- `ChatHistoryStore.writeRoot()` używa wspólnej blokady pliku `chat_state` przez cały odczyt kontrolny, walidację i commit.
- Test `directWriteRootWaitsForSharedPreferenceLockThroughValidationAndCommit` dodany; ADDED / NOT RUN.
- Commity: `f9d566f9d837b8aea8596cc5ad2bb89d22caa5a0`, `4028df55984d1b0c19927c3abeed9659dcf0ce22`.
- CI 42411603439 nadal w toku dla starszego SHA `30e0f496...`.
