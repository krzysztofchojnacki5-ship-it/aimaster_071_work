# AI MASTER PRO — checkpoint audytu 0.6.137
Data: 2026-11-06
Gałąź: `audit/0.6.137-routing-hardening`
Zakres: routing, scheduler, rozliczanie użycia API, timeouty i anulowanie agentów.

## Stan repozytorium
- HEAD gałęzi roboczej: `ea0f8f70cc01f0b81f3eaab3752bc92a27862483`
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
