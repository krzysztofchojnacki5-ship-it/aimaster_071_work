# AI MASTER PRO — checkpoint audytu 0.6.137
Data: 2026-11-06
Gałąź: `audit/0.6.137-routing-hardening`
Zakres: routing, scheduler, rozliczanie użycia API, timeouty i anulowanie agentów.

## Stan repozytorium
- HEAD gałęzi roboczej: `cb03f6ed66c0a40d4566f7ea8eb999d911124486`
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
