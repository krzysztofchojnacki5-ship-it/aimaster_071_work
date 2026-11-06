# Status budowania i weryfikacji — AI MASTER PRO 0.6.17.1

## Wersja
- `versionName = 0.6.17.1`
- `versionCode = 41`

## Zmiany
- Automatyczne uruchamianie dwóch recenzentów patcha z Centrum dowodzenia przez istniejący `TeamRunEngine`.
- Nowy `PatchPeerReviewPlanner`: różni agenci + różne przypisane profile modelowe; odrzucanie duplikatów modelu.
- Tylko profile zweryfikowane jako darmowe/lokalne; brak płatnego fallbacku.
- Smart Budget wymaga rezerwacji na pełną rundę dwóch recenzji; nie uruchamia pojedynczej recenzji jako pozornej niezależnej kontroli.
- Wyniki logowane do dziennika współpracy, Learning Journal i checkpointów.
- Naprawiono agregację: pojedynczy `REJECT`, `REQUEST_CHANGES` lub zgłoszony `BLOCKER` pozostaje blokujący nawet bez drugiej odpowiedzi; dwa APPROVE nadal wymagają pełnego quorum.

## Weryfikacje
- `PatchPeerReviewEngine` + `PatchPeerReviewPlanner`: **21/21 testów PASS** w lokalnym runnerze JVM z lekkim stubem JUnit i stubem wejściowego `ProjectPatchEngine`. To testy logiki tych komponentów, nie pełna suite Gradle.
- `MainActivity.kt`: skan parsera Kotlin bez diagnostyk składni; pełna analiza typów nieosiągalna bez Android SDK/dependencies.
- `unzip -t` dla ZIP bazowego 0.6.17 — PASS.

## Nie wykonano
- `./gradlew testDebugUnitTest --offline` — NOT RUN: wrapper wymaga pobrania Gradle 8.13; `UnknownHostException: services.gradle.org`.
- Pełny Android build / APK — NOT RUN: brak dystrybucji Gradle i Android SDK.
- Testy sieciowe z rzeczywistymi modelami — NOT RUN; wymagają skonfigurowanych dwóch różnych, darmowych profili i dostępu do API.

## Ograniczenia i wymagania operacyjne
- Automatyczna recenzja wymaga dwóch różnych profili darmowych/lokalnych przypisanych do ról Recenzent, Programista i Badacz (planner wybiera dwie różne konfiguracje). Jeśli przypisania wskazują na ten sam model, runda jest blokowana.
- Recenzja jest wyłącznie analizą. Nie stosuje diffu i nie zapisuje plików.
- Heurystyki i LLM nie gwarantują wykrycia wszystkich błędów; pozytywny wynik nie zastępuje kompilacji ani testów.
- 0.6.18 pozostaje osobnym etapem: jawna zgoda użytkownika, snapshot i kontrolowany zapis.
