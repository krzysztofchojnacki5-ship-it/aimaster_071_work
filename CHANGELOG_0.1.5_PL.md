# AI Master Pro Android — 0.1.5

## Zespół agentów i status połączenia
- Dodano osobne przypisanie dostawcy/modelu do każdej roli agenta; ustawienia są zapisywane lokalnie.
- Dodano kontrolkę statusu: szara = nie sprawdzono, pomarańczowa = test trwa, zielona = API odpowiedziało poprawnie, czerwona = test nieudany lub brak konfiguracji.
- Test wykonuje rzeczywiste krótkie żądanie do API, a nie tylko sprawdza obecność klucza.
- Agenci wybrani do czatu korzystają z przypisanego dostawcy.
- MASTER sekwencyjnie deleguje zadania do Badacza, Programisty i Recenzenta; każdy z nich korzysta z własnego przypisanego dostawcy. Brak kompletnej konfiguracji agenta powoduje czytelny błąd zamiast udawania delegacji.
- Dziennik zapisuje dostawcę/model użyty przez agenta.

## Weryfikacja
- Przejrzano kod i strukturę projektu.
- Próba kompilacji `:app:assembleDebug` została podjęta, ale środowisko nie miało dostępu do `services.gradle.org`, więc Gradle nie mógł pobrać wymaganej dystrybucji. Kompilacja APK nie jest tutaj potwierdzona; należy ją wykonać w Android Studio z dostępem do sieci.
