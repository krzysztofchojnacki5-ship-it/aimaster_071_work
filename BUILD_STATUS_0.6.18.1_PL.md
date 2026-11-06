# Status budowania i weryfikacji — AI MASTER PRO 0.6.18.1

## Wersja
- `versionName = 0.6.18.1`
- `versionCode = 43`
- Baza funkcjonalna: 0.6.18 — bezpieczne stosowanie zaakceptowanego patcha w prywatnym workspace.

## Zmiany poprawkowe
- `ProjectWorkspaceStore.recoverInterruptedSwap()` usuwa pozostałości `.patch-staging-*` i ich manifesty dopiero wtedy, gdy para `current/` + `current.manifest` jest kompletna.
- Odzyskiwanie po przerwanym przełączeniu katalogów pozostaje priorytetowe: jeżeli aktywny workspace lub manifest zniknął, poprzedni katalog `.previous-*` jest przywracany przed sprzątaniem.
- Dodano testy regresyjne: odzyskanie `.previous-*` po symulowanym przerwaniu procesu oraz sprzątanie osieroconego patch stagingu.

## Weryfikacja wykonana w tej sesji
- Kompilacja czystych komponentów Kotlin/JVM (`ChangeSafetyGuard`, `ProjectPatchEngine`, `ProjectWorkspaceStore`) przez `kotlinc` — PASS.
- Testy `ProjectPatchEngineTest`, `ProjectWorkspaceStoreTest`, `ProjectWorkspaceStoreApplyTest`: **33/33 PASS**.
- Testy uruchomiono lokalnym runnerem zgodnym z użytym podzbiorem JUnit 4 (lekkie stuby adnotacji/assertów); nie jest to pełny Gradle/JUnit ani test Android UI.
- Kontrola składni/typów całej `MainActivity` — NOT RUN w pełnym Android classpath.
- Pełny Gradle / APK — NOT RUN: brak lokalnej dystrybucji Gradle 8.13 i Android SDK; wcześniejsza próba pobrania wrappera blokowała się na DNS `services.gradle.org`.

## Bezpieczeństwo i ograniczenia
- Zapis nadal dotyczy wyłącznie prywatnego workspace aplikacji, nigdy oryginalnego ZIP-a ani źródłowego repozytorium na komputerze.
- Po zastosowaniu zmiany status pozostaje `APPLIED_UNVERIFIED`.
- Izolowany runner testów projektu, weryfikacja PASS/FAIL/NOT_RUN i automatyczny rollback snapshotu pozostają zakresem 0.6.19.
