# Status budowania i weryfikacji — AI MASTER PRO 0.6.18

## Wersja
- `versionName = 0.6.18`
- `versionCode = 42`

## Zmiany
- Bezpieczne stosowanie zaakceptowanego `ProjectPatchEngine.Preview` w odizolowanym `ProjectWorkspaceStore`.
- Ponowna kontrola integralności manifestu i SHA-256 tuż przed zapisem; brak zapisu na podstawie starego preview.
- Snapshot przed zmianą, staging całego workspace, nowy manifest i transakcyjne przełączenie katalogu.
- Walidacja ścieżek, kolizji case-insensitive, operacji ADD/MODIFY/DELETE, hashy wynikowych, UTF-8, NUL, limitów i rozszerzeń tekstowych.
- UI wymaga dwóch pozytywnych recenzji i dwóch jawnych potwierdzeń użytkownika.
- `ChangeSetStore` przechowuje snapshotId i stan `APPLIED_UNVERIFIED`; status `APPLIED` wymaga PASS testów.
- Naprawiono zdublowaną deklarację `symlink` w `ProjectWorkspaceStore`, która blokowała kompilację.

## Weryfikacje
- Kompilacja czystego Kotlin/JVM `ProjectWorkspaceStore` + `ProjectPatchEngine` + `ChangeSafetyGuard` — PASS.
- Testy logiki Peer Review, planera recenzji i rzeczywistego zapisu workspace: **28/28 PASS** w lokalnym runnerze JVM ze stubem JUnit; bez Gradle.
- Skan składni `MainActivity.kt`: brak diagnostyk parsera; pełne sprawdzenie typów UI nie jest dostępne bez Android SDK/dependencies.
- ZIP bazowy 0.6.17.1 przechodzi `unzip -t` — PASS.

## Nie wykonano
- `./gradlew testDebugUnitTest --offline` — NOT RUN: brak lokalnej dystrybucji Gradle 8.13; wrapper wymaga pobrania `services.gradle.org`, DNS zwraca `UnknownHostException`.
- Testy Robolectric `ChangeSetStore` — NOT RUN bez Gradle i zależności.
- Pełny Android build / APK — NOT RUN.
- Recenzja API z rzeczywistymi modelami — NOT RUN; wymaga skonfigurowanych dwóch różnych darmowych profili i dostępu do API.

## Ograniczenia
- Zmiany są stosowane wyłącznie w prywatnym workspace aplikacji, nigdy bezpośrednio w oryginalnym projekcie na komputerze.
- Po zapisie status to `APPLIED_UNVERIFIED`; dopiero 0.6.19 ma uruchomić izolowane testy, zatwierdzić `APPLIED` albo przywrócić snapshot.
- Jeśli aktualizacja rejestru zmian zawiedzie po udanym zapisie, UI pokaże ostrzeżenie i identyfikator snapshotu; nie należy ponawiać patcha w ciemno.
