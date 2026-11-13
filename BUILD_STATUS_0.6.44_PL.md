# AI MASTER PRO 0.6.44 — status wydania

## Zakres
Kontynuacja punktu 5 listy: wymuszenie aktualnego stanu zaufania peer przez `CompanionSessionOwner`, również po rotacji lub unieważnieniu zapisanym w trwałym magazynie.

## Zmiany
- `CompanionSessionOwner` wymaga walidatora `(peerId, SPKI) -> trusted`; konstruktor jest wewnętrzny dla modułu. Preferowana ścieżka `CompanionProvisioningStore.createSessionOwner()` przekazuje walidator odczytujący aktualny, odszyfrowany magazyn.
- `activate(receipt)` odrzuca peer, który nie jest już zaufany (`PEER_REVOKED`), nawet jeśli TLS receipt został wydany przed unieważnieniem.
- Każde `verifyIncoming`, `beginTransfer`, `finishTransferAfterValidation`, `pollTimeout` oraz odczyt wewnętrznej tożsamości TLS sprawdza aktualny pin. Brak zaufania lub błąd walidacji kończy sesję stanem `FAILED`, zamyka receiver i czyści metadane sesji.
- Wyjątek walidatora traktowany jest jako brak zaufania (fail-closed).
- Stage next zachowuje aktualny pin; promote usuwa stary pin, więc stara sesja zostaje odrzucona przy następnej operacji.

## Zmienione pliki
- `app/src/main/java/pl/aimasterpro/app/core/CompanionSessionOwner.kt`
- `app/src/main/java/pl/aimasterpro/app/data/CompanionProvisioningStore.kt`
- `app/src/test/java/pl/aimasterpro/app/core/CompanionSessionOwnerTest.kt`
- `app/build.gradle.kts` (versionCode 70, versionName 0.6.44)
- `PROJECT_WORKSPACE_ROADMAP_PL.md`
- `BUILD_STATUS_0.6.44_PL.md`
- `CHANGELOG_0.6.44_COMPANION_TRUST_REVOCATION_ENFORCEMENT_PL.md`
- `AGENT_HANDOFF_PROMPT_PL.md`

## Walidacja
- `CompanionSessionOwnerTest` + `CompanionTlsContextTest`: **31/31 PASS**.
- Pięć pełnych powtórzeń zestawu: **155/155 PASS**.
- `kotlinc -Werror` dla wybranych klas core oraz `CompanionProvisioningStore` z atrapami API Android/JSON: **PASS** (kontrola składni i typów; atrapy nie testują platformy).
- Pełny Gradle/Robolectric: **NOT RUN**.
- Android Keystore na urządzeniu/emulatorze, APK, instalacja i test urządzeniowy: **NOT RUN**.
- `PROJECT_TESTS`: **NOT RUN**.

## Ograniczenia i otwarte bramki
- Walidacja odcina sesję przy następnym wywołaniu API ownera. Owner nie zarządza jeszcze socketem TLS, więc nie może natychmiast zamknąć już otwartego gniazda w chwili revocation. Nie ma jeszcze transportu aplikacyjnego.
- Nadal wymagane: certyfikat TLS z poprawnym SAN i zaufanym łańcuchem oraz proces wydania/provisioningu; bezpieczna migracja PSK; recovery/re-pairing; testy prawdziwego Android Keystore i platformowego buildu.
- Transport, Desktop Companion, OS sandbox i `PROJECT_TESTS` pozostają zablokowane do zamknięcia punktu 5 i kolejnych bramek.

## Pochodzenie źródeł
Kontynuacja bazy 0.6.41/0.6.42 opartej na rekonstrukcji 0.6.40 z odzyskanego 0.6.39. Nie jest to oryginalny odzyskany ZIP 0.6.40.
