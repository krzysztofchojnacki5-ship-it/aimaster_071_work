# Status budowy AI MASTER PRO 0.6.41

- Wersja: `0.6.41` (`versionCode=67`).
- Baza: rekonstrukcja źródeł 0.6.40 oparta na odzyskanym archiwum 0.6.39.
- Główna zmiana: jednorazowy, niepodrabialny dowód tożsamości TLS peer związany z konkretnym profilem TLS i `CompanionSessionOwner`.
- Peer SPKI jest przypisany do konkretnego `peerId`, nie do wspólnej listy bez mapowania tożsamości.
- `CompanionSessionOwner.activate()` bez dowodu TLS: odrzucenie `TLS_IDENTITY_REQUIRED`.
- Obcy profil, obcy receipt, ponowne użycie receipt: odrzucane.
- Kompilacja wybranych klas Kotlin/JVM z `-Werror`: PASS.
- `CompanionSessionOwnerTest` + `CompanionTlsContextTest`: 29/29 PASS.
- Powtórzenia: 5 × 29/29 = 145/145 PASS.
- Rzeczywisty TLS/mTLS loopback: PASS.
- Gradle/Robolectric: NOT RUN; próba `./gradlew :app:testDebugUnitTest` zatrzymała się przed startem testów, ponieważ środowisko nie mogło rozwiązać `services.gradle.org` i pobrać Gradle 8.13.
- APK / urządzenie Android: NOT RUN.
- Android Keystore provisioning: NOT IMPLEMENTED.
- Rotacja/unieważnianie pinów: NOT IMPLEMENTED.
- Companion transport/Desktop Companion: NOT IMPLEMENTED.
- OS-level sandbox: NOT IMPLEMENTED.
- `PROJECT_TESTS`: NOT RUN.

## Ograniczenia

Profil i mapowanie peer ID → SPKI są dostarczane przez warstwę provisioningu aplikacji. Ta wersja tworzy bezpieczniejszą granicę użycia TLS, ale nie wdraża jeszcze trwałego, odpornego na manipulację provisioningu w Android Keystore ani protokołu rotacji/unieważniania. Nie należy uruchamiać produkcyjnego transportu, dopóki te elementy i testy Android/Gradle nie zostaną ukończone.
