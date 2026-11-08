# Instrukcja budowania i instalacji APK (WB-007)

Niniejszy dokument opisuje powtarzalną ścieżkę odtworzenia środowiska, zbudowania pakietu debug APK, uruchomienia testów oraz instalacji aplikacji **AI MASTER PRO** na urządzeniu lub emulatorze z poziomu Android Studio lub linii poleceń.

## 1. Wymagania wstępne
- **System operacyjny:** Windows 11 / Linux / macOS
- **IDE:** Android Studio (Ladybug / Koala lub nowszy)
- **JDK:** Java Development Kit 17 (zalecany JetBrains Runtime / JBR dostarczany z Android Studio, np. `C:\Program Files\Android\Android Studio\jbr`)
- **Android SDK:** API 36 (`compileSdk 36`, `minSdk 26`, Build-Tools 36.0.0, Platform-Tools)
- **Gradle:** Gradle Wrapper 8.13 (skrypt `gradlew` / `gradlew.bat` oraz `gradle/wrapper/gradle-wrapper.jar`)

---

## 2. Klonowanie i przygotowanie gałęzi
1. Sklonuj repozytorium:
   ```bash
   git clone https://github.com/krzysztofchojnacki5-ship-it/aimaster_071_work.git
   cd aimaster_071_work
   ```
2. Przełącz się na gałąź zadaniową WB-007:
   ```bash
   git checkout build/WB-007-android-apk-pipeline
   ```

---

## 3. Konfiguracja środowiska w Android Studio
1. Otwórz folder główny projektu w Android Studio.
2. Upewnij się, że w **Project Structure** (`File > Project Structure > SDK Location`) ścieżka do Android SDK wskazuje lokalny folder SDK (np. `C:\Users\<user>\AppData\Local\Android\Sdk`).
3. Upewnij się, że **Gradle JDK** ustawione jest na JDK 17 (JBR 17).
4. Wykonaj **Gradle Sync** (`File > Sync Project with Gradle Files`).

---

## 4. Budowanie i testowanie z linii poleceń
Upewnij się, że zmienna środowiskowa `JAVA_HOME` wskazuje JDK 17:
- **Windows (PowerShell):**
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  ```
- **Linux / macOS:**
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```

### Uruchomienie testów jednostkowych:
```bash
./gradlew :app:testDebugUnitTest
```

### Zbudowanie Debug APK:
```bash
./gradlew :app:assembleDebug
```

---

## 5. Lokalizowanie i instalacja APK
Po zakończeniu budowania plik APK znajduje się w ścieżce:
`app/build/outputs/apk/debug/app-debug.apk`

### Instalacja na podłączonym urządzeniu / emulatorze przez ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 6. Rozwiązywanie typowych problemów (Troubleshooting)
- **Błąd JAVA_HOME / Nie znaleziono javac:** Ustaw zmienną `JAVA_HOME` na ścieżkę do JDK 17.
- **Błąd brakującego Gradle Wrapper (`gradle-wrapper.jar`):** Wrapper 8.13 jest w pełni dołączony w repozytorium w katalogu `gradle/wrapper/`.
- **Błąd brakującego SDK API 36:** Otwórz SDK Manager w Android Studio i zainstaluj *Android SDK Platform 36*.
