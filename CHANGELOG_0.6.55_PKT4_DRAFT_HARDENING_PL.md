# Changelog — AI MASTER PRO 0.6.55 (pakiet źródłowy)

## Utwardzenie szkicu pkt 4
- Zastąpiono kolekcje oparte na równości obiektów kolekcjami opartymi na tożsamości uchwytu.
- Dodano mapę właściciela uchwytu; pojedynczy `Closeable` nie może być jednocześnie własnością dwóch peerów.
- Duplikat rejestracji w ramach tego samego peera jest idempotentny.
- Próba przeniesienia uchwytu do innego peera jest odrzucana fail-closed.
- `release` nie może usunąć uchwytu należącego do innego peera.
- Dodano walidację identyfikatora peera i ochronę przed przepełnieniem epoki.
- Rozszerzono szkic testów o równe, ale różne obiekty, konflikt właściciela, błędne release i współbieżność.

## Dokumentacja
- Zaktualizowano `drafts/pkt4/README_DRAFT_PL.md` z dokładnymi granicami integracji.
- Dodano `PACKAGE_REGISTRY_PL.md`.
- Zaktualizowano status, README, roadmapę, architekturę i handoff.

## Walidacja
- Lokalny kompilator Kotlin `kotlinc -Werror` dla klasy rejestru: PASS.
- Lokalny harness uruchomił 13 metod testowych ze szkicu: 13/13 PASS; użyto własnego shim-u asercji, więc nie jest to JUnit/Gradle.
- Osobny smoke test, w tym 1000 wyścigów register/revoke: PASS.
- JUnit/Gradle/Android: NOT RUN.

## Ważne
To nie jest zweryfikowane wydanie APK. `app/build.gradle.kts` nadal wskazuje 0.6.54 / versionCode 80. Szkic pkt 4 pozostaje poza `app/src` i nie jest aktywny w aplikacji.
