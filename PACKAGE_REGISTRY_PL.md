# AI MASTER PRO — rejestr paczek i artefaktów

Rejestr identyfikuje wydania aplikacji, paczki źródłowe i rzeczywiście utworzone artefakty. Numer paczki źródłowej nie oznacza automatycznie istnienia APK.

## Bieżący stan

- **Aplikacja:** AI MASTER PRO Android
- **Application ID:** `pl.aimasterpro.app`
- **Wersja źródeł aplikacji w aktualnej bazie:** `0.6.54`
- **versionCode:** `80`
- **Gradle Wrapper:** `8.13`
- **AGP:** `8.13.2`
- **Kotlin:** `2.0.21`
- **Bieżący status builda:** patrz [BUILD_STATUS.md](BUILD_STATUS.md)
- **Instrukcja budowania:** [BUILD_AND_INSTALL_APK_PL.md](BUILD_AND_INSTALL_APK_PL.md)

## Zasady rejestru

- Nie wpisywać APK jako utworzonego bez pliku z udanego `:app:assembleDebug`.
- Dla każdego APK zapisać commit źródłowy, datę, wersję aplikacji, versionCode i SHA-256.
- Testy jednostkowe i kompilację APK rejestrować oddzielnie.
- Historyczne raporty buildów są w `docs/archive/build-status/`; nie są bieżącym źródłem statusu.
