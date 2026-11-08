# Status Budowania v0.6.150 (WB-007)

**Data audytu / budowania:** 2026-11-08
**Gałąź:** `build/WB-007-android-apk-pipeline`
**Base SHA:** `e37c3eded7be0ac8f92532e35cbb1e6f0dcb7eca`

## Konfiguracja środowiska budowania
- **Android Gradle Plugin (AGP):** 8.13.2
- **Kotlin Version:** 2.0.21
- **Gradle Version:** 8.13
- **JDK / JBR:** JDK 17 (JetBrains Runtime)
- **Target / Compile SDK:** API 36
- **Min SDK:** API 26

## Wyniki kompilacji i artefakty
- **Zadanie Gradle:** `:app:assembleDebug`
- **Ścieżka wynikowa APK:** `app/build/outputs/apk/debug/app-debug.apk`
- **Status budowania:** Odtworzona w pełni sprawna ścieżka Gradle Wrapper 8.13 z automatycznym pobieraniem i konfiguracją kompilacji.
- **Automatyzacja CI:** Zaktualizowano `.github/workflows/android-audit.yml` do obsługi gałęzi `build/WB-007-android-apk-pipeline`, rozdzielenia testów jednostkowych i assembleDebug oraz publikacji artefaktu `debug-apk`.
