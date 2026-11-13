# AI MASTER PRO 0.6.18.1 — utwardzenie odzyskiwania po przerwanym zapisie

## Naprawa
- Po przerwaniu procesu podczas przygotowania patcha mogły pozostać katalogi `.patch-staging-*` i pliki manifestu stagingu.
- Odzyskiwanie workspace sprząta teraz zarówno staging importu, jak i staging patcha, ale wyłącznie po potwierdzeniu kompletnej pary aktywnego katalogu i manifestu.
- Jeżeli para aktywna jest niekompletna, najpierw podejmowana jest próba przywrócenia poprzedniego workspace z `.previous-*`.

## Testy regresyjne
- Symulowane przerwanie procesu po przeniesieniu `current/` i `current.manifest` do `.previous-*`; następny odczyt odtwarza poprzedni projekt.
- Osierocony `.patch-staging-*` i jego manifest są usuwane, gdy aktywny workspace jest poprawny.
- Testy parsera diffu, importu workspace, integralności, snapshotów i stosowania patcha: 33/33 PASS w lokalnym runnerze JVM ze stubem JUnit.

## Granice
- To poprawka odporności workspace, nie pełny runner testów projektu.
- Gradle/Android build i APK pozostają NOT RUN z powodu niedostępnego Gradle 8.13 / Android SDK.
- 0.6.19 nadal obejmuje izolowane testowanie zaakceptowanych zmian, klasyfikację PASS/FAIL/NOT_RUN oraz automatyczne przywrócenie snapshotu po błędzie.
