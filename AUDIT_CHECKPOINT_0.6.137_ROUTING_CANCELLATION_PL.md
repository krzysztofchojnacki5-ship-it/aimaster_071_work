# AI MASTER PRO — checkpoint audytu 0.6.137
Data: 2026-11-06
Gałąź: `audit/0.6.137-routing-hardening`
Zakres: routing, scheduler, rozliczanie użycia API, timeouty i anulowanie agentów.

## Stan repozytorium
- Ostatni HEAD kodu przed aktualizacją tego checkpointu: `74ed3899081bece81d2ff4b77f0e21d2f5769e25`
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
