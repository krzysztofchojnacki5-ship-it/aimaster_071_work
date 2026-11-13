# Status budowania — 0.6.13

- Dodano `ProjectWorkspaceStore` z bezpiecznym rozpakowywaniem ZIP, manifestem SHA-256 i snapshotem poprzedniego workspace.
- Dodano 7 testów jednostkowych; wszystkie 7 uruchomiono w izolowanym harnessie JUnit-stub i zakończyły się PASS.
- Niezależnie skompilowano `ProjectWorkspaceStore.kt` i testy Kotlin; kompilacja obu zakończyła się PASS.
- Smoke test importu, filtrowania sekretów, odrzucania ZIP Slip, manifestu i snapshotu zakończył się PASS.
- Dodano import ZIP i podgląd manifestu w Centrum.
- Pełne `testDebugUnitTest` nie wystartowało: Gradle Wrapper nie może pobrać Gradle 8.13 z powodu `UnknownHostException: services.gradle.org`.
- `assembleDebug` nie wykonano. Nie utworzono APK.
- Nie wykonano pełnej kompilacji Android/MainActivity: w środowisku brak Android SDK (`android.jar`). Parser Kotlin MainActivity nie zgłosił błędów składni, ale to nie zastępuje kompilacji Android.
