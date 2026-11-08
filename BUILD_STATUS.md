# AI MASTER PRO — bieżący status budowania

**Jedyny aktualny status buildów.** Historyczne raporty wersji znajdują się w `docs/archive/build-status/` i nie mogą być traktowane jako aktualny wynik kompilacji.

## Źródło wersji narzędzi

| Element | Wartość repozytorium |
|---|---|
| Gradle | 8.13 — wyłącznie przez Gradle Wrapper |
| Android Gradle Plugin | 8.13.2 |
| Kotlin Gradle Plugin | 2.0.21 |
| JDK CI | 17 |
| Lokalny Android Studio JBR | 21 (wykryty w logach użytkownika; nie zmienia wersji Wrappera) |
| Android SDK | compileSdk 36 |
| Java/Kotlin bytecode target | 17 |

Plik `gradle/wrapper/gradle-wrapper.properties` jest jedynym źródłem wersji Gradle. Inne katalogi w `%USERPROFILE%\.gradle\daemon\` są lokalnymi cache/demonami różnych wersji i nie oznaczają, że projekt używa kilku wersji jednocześnie.

## Aktualny pipeline WB-007

- Branch: `build/WB-007-android-apk-pipeline`
- Ostatni commit: `5c36b7b797f951c438b754cd298f542ba23c12f5`
- Poprawki w repo: weryfikacja SHA-256 Wrapper JAR, wyłączenie niezgodnej wbudowanej walidacji po zastąpieniu jej pinem SHA-256, usunięcie nieadekwatnej właściwości Gradle 9.4+, poprawienie skryptów Wrappera i prawa wykonywania `gradlew`, bezpośredni host dystrybucji Gradle oraz timeout 120 s i trzy próby w kroku weryfikacji.
- Ostatnie dwa rzeczywiste CI: run `42466571680` na commicie `25e09aa...` zakończył się **FAILURE** przez `./gradlew: Permission denied` (exit 126); run `42466639638` na commicie `47e5aae...` zakończył się **FAILURE** przez HTTP 504 podczas pobierania dystrybucji Gradle 8.13 z przekierowania GitHub. Oba problemy mają poprawki w repo.
- Najnowsze poprawki pobierania dystrybucji znajdują się w commicie `5c36b7b...`; **nie ma jeszcze wyniku CI dla tego commita**.
- Testy jednostkowe: **NOT RUN na aktualnym commicie**.
- `:app:assembleDebug`: **NOT RUN na aktualnym commicie**.
- APK i SHA-256 APK: **NOT GENERATED / NOT VERIFIED**.
- Lokalny build Android Studio: **NOT VERIFIED** — dostępne logi użytkownika nie zawierają kompletnego końcowego wyniku zadania.

## Reguły statusu

1. PASS wyłącznie po rzeczywistym exit code 0.
2. Testy i assembleDebug raportować osobno.
3. APK uznać za istniejące dopiero po sprawdzeniu pliku i jego SHA-256.
4. Nie podnosić wersji Gradle/AGP na podstawie sugestii Upgrade Assistant bez osobnego audytu kompatybilności.
5. Nie usuwać lokalnych cache Gradle jako „naprawy” bez potwierdzonego powodu.

## Historia

Raporty `BUILD_STATUS_*.md` przeniesiono bez zmiany treści do `docs/archive/build-status/`. Są archiwum historycznym, a nie bieżącym dashboardem.
