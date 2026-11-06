# Instrukcja: co uruchomić w domu i co odesłać

Ta instrukcja obejmuje wszystko, czego nie dało się zweryfikować w środowisku, w którym powstał kod (brak Kotlin, Gradle, Android SDK, dostępu do sieci i urządzenia).

## A. Przygotowanie
1. Rozpakuj `AI_MASTER_PRO_0_6_52_COMPANION_TRUST_UI_SOURCE.zip` do osobnego katalogu.
2. Otwórz projekt w Android Studio (albo użyj wiersza poleceń z zainstalowanym Android SDK).
3. Projekt celuje w `compileSdk 36`, `minSdk 26`, Java 17 (`jvmTarget 17`). Zainstaluj odpowiedni Android SDK Platform i JDK 17.
4. Przy pierwszym uruchomieniu Gradle pobiera zależności (w tym Robolectric). Potrzebne jest połączenie z internetem.

## B. Testy jednostkowe (JVM) — najważniejsze
Uruchom z katalogu projektu:
```
./gradlew testDebugUnitTest
```
Zapisz i wklej:
1. Ostatnie ~60 linii wyjścia (podsumowanie `BUILD SUCCESSFUL` lub `BUILD FAILED`).
2. Jeśli są błędy kompilacji: pełne komunikaty `e: ...` z nazwami plików i numerami linii.
3. Jeśli są niezaliczone testy: nazwy klas i testów oraz komunikaty asercji.

Szczególnie sprawdź klasy dodane lub zmienione w 0.6.45–0.6.52:
- `CompanionTlsLeafPolicyTest` (12 testów)
- `CompanionCsrBuilderTest` (7)
- `CompanionTlsIssuanceFlowTest` (7)
- `CompanionTlsChainRepositoryTest` (13)
- `CompanionPskRepositoryTest` (9)
- `CompanionTrustRecoveryTest` (10)
- `CompanionSessionOwnerTest` (18 testów, w tym 2 nowe z 0.6.45; wcześniejszy wynik 31/31 dotyczył łącznie tej klasy i `CompanionTlsContextTest` na starej bazie)

Jeśli to możliwe, uruchom też same te klasy:
```
./gradlew testDebugUnitTest --tests "pl.aimasterpro.app.core.Companion*"
```

## C. Kompilacja aplikacji
```
./gradlew assembleDebug
```
Wklej wynik tak samo jak w punkcie B.

## D. Testy ręczne na urządzeniu (tylko jeśli B i C przechodzą)
1. **Klucze API dostawców (poprawka SecureStore):** dodaj dostawcę z kluczem, zamknij i uruchom aplikację ponownie, sprawdź że klucz nadal jest widoczny w edycji dostawcy. Odpisz: TAK/NIE.
2. **Import konfiguracji:** eksportuj bazę z hasłem, zaimportuj ją. Przy złym pliku powinien pojawić się komunikat błędu, a stare konfiguracje mają zostać. Odpisz co się stało.
3. **Karta „Zaufanie Companion” w Diagnostyce:** sprawdź, czy się wyświetla i jaki tekst pokazuje (powinno być „Brak zapisanego zaufania…”). Odpisz tekst.
4. **Odzyskiwanie:** otwórz okno odzyskiwania, wpisz zły tekst (nic nie powinno się zmienić), potem anuluj. Odpisz co się stało.
5. **Keystore:** czy aplikacja się nie wywala przy pierwszym dostępie do Keystore (ekran Diagnostyka i Modele).

## E. Co pozostaje do zrobienia po wynikach
- Naprawa błędów kompilacji i testów, które pokażesz.
- Pkt 4 (projekt w `docs/SOCKET_OWNERSHIP_REVOCATION_DESIGN_PL.md`) — dopiero po zielonych testach.

## F. Uczciwość wyników
Każdy wynik, który odeślesz, zostanie zapisany w statusie wersji. „PASS” pojawi się wyłącznie dla testów faktycznie uruchomionych.
