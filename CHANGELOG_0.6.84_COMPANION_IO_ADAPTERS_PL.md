# 0.6.84-SOURCE — adaptery I/O i synchronizacja trust store

- Baza: 0.6.83-SOURCE.
- Wydzielono `CompanionTrustIo` z domyślną implementacją Android/JVM dla commitów preferences, atomowego rename, usuwania plików, fsync katalogu i usuwania aliasu Keystore.
- Adapter można podmienić w testach, aby wymuszać `commit() == false` i wyjątek `KeyStore.deleteEntry()` bez polegania na ręcznym fault callbacku.
- Współdzielony monitor i fail-closed latch przeniesiono do statycznego stanu procesu aplikacji; różne instancje store w jednym procesie nie powinny wykonywać równoległych trust mutations.
- Dodano trzy testy Robolectric: false commit + recovery, błąd usunięcia aliasu oraz serializacja dwóch instancji. Testy ADDED / NOT RUN.
- Brak APK; `versionName=0.6.54`, `versionCode=80`. Gradle/Robolectric/Android Keystore/device NOT RUN. Transport pozostaje BLOCKED.
