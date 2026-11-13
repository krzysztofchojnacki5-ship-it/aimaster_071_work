# 0.6.72-SOURCE — Application-scoped Companion Graph

## Zakres

Pochodna 0.6.71-SOURCE. Etap przygotowuje jeden procesowy graph zależności Companion, ale celowo nie instaluje jeszcze draftów w produkcyjnym source set i nie deklaruje klasy Android `Application`.

## Ustalenia z audytu bazy 0.6.71

- `AndroidManifest.xml` nie wskazuje własnej klasy `Application`.
- `CompanionTrustService(context)` tworzył własny `CompanionProvisioningStore` przy każdym konstruktorze.
- `MainActivity` tworzy `CompanionTrustService(this)` w dwóch miejscach (odczyt stanu i jawne odzyskiwanie zaufania).
- `CompanionRuntime`, registry i binding pozostają w `drafts/pkt4`.
- `CompanionProvisioningStore` implementuje `CompanionTrustRevocationSource`; jeden wspólny store jest konieczny, aby binding obserwował ten sam publisher, do którego trafiają operacje revoke/wipe.

## Zmiany

- Dodano `drafts/pkt4/CompanionApplicationGraph.kt`.
  - Lazily tworzy dokładnie jeden store przez `lazy(SYNCHRONIZED)`.
  - Wszystkie wywołania `provisioningStore()` otrzymują tę samą instancję.
  - Jeden `CompanionRuntimeSlot` tworzy runtime powiązany z tym samym store.
  - `close()` zamyka runtime/binding, ale nie kasuje trwałego trust store.
  - Po zamknięciu następne `runtime()` może utworzyć świeży runtime/registry, bez odtwarzania socketów ani admission tickets.
  - Graph nie przechowuje Android `Context`; przyszła klasa `Application` ma przekazać factory budującą store z `applicationContext`.
- Dodano `CompanionApplicationGraphTest.kt`: współbieżna inicjalizacja store, współdzielenie i odtworzenie runtime po close, ponowienie factory po wyjątku.
- `CompanionTrustService` otrzymał parametr `CompanionProvisioningStore` z dotychczasowym konstruktorem domyślnym. To seam do wstrzyknięcia store z graphu; obecne call sites MainActivity nadal korzystają z domyślnego tworzenia i NIE są jeszcze przepięte.
- Zaktualizowano README szkicu, status, rejestr paczek i handoff.

## Walidacja

- `kotlinc -Werror` dla interfejsu revocation, registry, binding, runtime, graph i testów graph: PASS.
- `CompanionApplicationGraphTest`: 3/3 PASS lokalny JVM harness.
- ZIP integrity: PASS.
- Testy z Android Studio/Gradle/JUnit/Robolectric, `assembleDebug`, Android Keystore i urządzenie: NOT RUN.

## Granice / ryzyka

- Nie dodano jeszcze klasy `Application` ani `android:name` w manifeście.
- MainActivity nadal tworzy usługę bez wstrzykniętego store; realne współdzielenie w UI wymaga następnego kontrolowanego kroku.
- Runtime/registry/binding/graph nadal są poza produkcyjnym source set.
- Nie powstał APK; `versionName=0.6.54`, `versionCode=80`.

## Następny krok

0.6.73: zaprojektować i przetestować granicę `Application` (właściciel graphu, inicjalizacja raz na proces, zamykanie bez wyścigu z odtwarzaniem runtime), następnie przepiąć oba call sites `MainActivity` na ten sam store dopiero po przeniesieniu wymaganych klas do właściwego source set i uruchomieniu pełnych testów Gradle.
