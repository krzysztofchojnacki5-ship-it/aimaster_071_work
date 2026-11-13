# BUILD STATUS — AI MASTER PRO 0.6.99-SOURCE

- Baza: `AI_MASTER_PRO_0_6_98_WORKSPACE_PROCESS_LOCK_SOURCE.zip`.
- Nowa paczka: `AI_MASTER_PRO_0_6_99_ROLLBACK_WORKSPACE_IDENTITY_GUARD_SOURCE.zip`.
- Wersja aplikacji pozostaje `0.6.54`, `versionCode=80`; nie utworzono APK.

## Zmiany
- Weryfikacja workspace i `currentIdentity()` są wykonywane w jednej sekcji `ProjectWorkspaceOperationLock`; rezultat przenosi dokładną tożsamość workspace z chwili weryfikacji.
- Automatyczny rollback wymaga identycznej tożsamości przed przywróceniem snapshotu. Jeżeli w międzyczasie zaimportowano lub podmieniono workspace, rollback jest blokowany — nowsze źródła nie zostaną nadpisane starym snapshotem.
- Dodano `ProjectWorkspaceIdentityGuard` i testy dla zgodnej, zmienionej oraz brakującej tożsamości.
- Import i unieważnienie starego active evidence scope odbywają się w jednej globalnej sekcji krytycznej. Nowy patch nie może zostać aktywowany pomiędzy importem a opóźnionym czyszczeniem wskaźnika.
- Unieważnienie po imporcie jest raportowane osobno; wyjątek podczas czyszczenia nie zmienia prawdziwego wyniku importu na fałszywe „import odrzucony”. Resolver nadal sprawdza workspace identity i działa fail-closed.
- Po udanym rollbacku aktywny scope jest unieważniany pod tą samą blokadą. Niepowodzenie zapisu unieważnienia jest jawnie raportowane; mismatch workspace identity uniemożliwia użycie starego wskaźnika.

## Walidacja wykonana
- `bash tools/verification_resolver_harness/run.sh`: **73/73 PASS**, `kotlinc -Werror`.
- `bash tools/changeset_store_compile_harness/run.sh`: PASS (`ChangeSetStore` + `HostActiveChangeSetStore`, compile-only Android/JSON shims, `-Werror`).
- Statyczny przegląd przepływu import/apply/verification/rollback i call sites: PASS dla nowych warunków tożsamości i kolejności blokad.
- `unzip -t`: PASS.

## Nie wykonano
- Gradle, JUnit/Robolectric, pełny Android build, APK, testy urządzeniowe — NOT RUN.
- Companion transport — nadal BLOCKED / nieuruchamiany.

## Ograniczenia
- Testy 73/73 są lokalnym JVM shim harness, nie pełnym zestawem Android/JUnit.
- Procesowy lock serializuje hostowe operacje tej aplikacji; nie jest blokadą systemową dla modyfikacji plików przez root/ADB lub zewnętrzny proces.
