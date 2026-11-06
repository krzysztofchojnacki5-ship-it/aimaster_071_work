# AI MASTER PRO 0.6.17.1 — Automatyczny Patch Peer Review

## Nowe elementy
- Centrum dowodzenia uruchamia dwie recenzje rzeczywistego diffu przez istniejący `TeamRunEngine`; nie powstaje równoległy klient ani drugi mechanizm orkiestracji.
- Dodano `PatchPeerReviewPlanner`, który wybiera dwóch różnych agentów i wymaga różnych, jawnie przypisanych profili modelowych. Identyczne profile (ten sam dostawca/model/endpoint) nie są przedstawiane jako niezależne recenzje.
- Do recenzji kwalifikują się wyłącznie profile rozpoznane jako darmowe/lokalne. Brak płatnego fallbacku.
- Smart Budget rezerwuje budżet na obie recenzje; jeśli nie można uruchomić kompletu, system nie uruchamia tylko jednej. Zachowuje rezerwę tokenów i rejestruje rzeczywiste użycie.
- Wyniki trafiają do dziennika współpracy, Learning Journal i TaskCheckpointStore. Agregator wymaga dwóch strukturalnych werdyktów APPROVE, aby przejść do `READY_FOR_HUMAN_APPROVAL`.
- UI wyświetla raport recenzentów. Recenzja nie zapisuje ani nie stosuje zmian.

## Naprawa wykrytego błędu
- W `PatchPeerReviewEngine.combine` pojedynczy jawny `REJECT`, `REQUEST_CHANGES` lub raportowany `BLOCKER` nie może być już ukryty przez brak drugiej recenzji. Quorum dwóch recenzentów jest wymagane do pozytywnej akceptacji, nie do zgłoszenia blokady.

## Zabezpieczenia
- Dwie recenzje muszą pochodzić od różnych agentów i różnych fingerprintów profili modelowych.
- Brak automatycznego fallbacku do płatnego lub tego samego modelu.
- Niepełny budżet blokuje całą rundę, aby uniknąć pozornej niezależności na podstawie jednej opinii.
- Żaden werdykt modelu nie daje zgody na zapis. Nadal wymagany jest jawny etap 0.6.18.

## Weryfikacja
- Testy czystego Kotlin/JVM dla `PatchPeerReviewEngine` i `PatchPeerReviewPlanner`: **21/21 PASS** w lokalnym runnerze zgodnym z użytym podzbiorem JUnit; testy uruchomione z lekkim stubem `ProjectPatchEngine`, bez Gradle.
- `MainActivity.kt`: skan diagnostyk parsera Kotlin nie wykazał błędów składni (`expecting`, `unexpected tokens`, niedomknięte konstrukcje); pełna kontrola typów Android UI nie była możliwa bez Android SDK i zależności Gradle.
- `./gradlew testDebugUnitTest --offline` — **NOT RUN**: wrapper próbował pobrać Gradle 8.13; DNS `services.gradle.org` zwrócił `UnknownHostException`.
- Pełny Android build i APK — **NOT RUN**.
- ZIP źródłowy — test integralności `unzip -t` PASS.
