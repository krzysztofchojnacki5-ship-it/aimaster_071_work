# Zmiany w v0.1.3

## Dodano
- Trwały lokalny zapis pojedynczego wątku czatu i odtworzenie go po restarcie aplikacji.
- Trwałe zapamiętanie wybranego dostawcy/modelu do czatu.
- Widoczny status wybranego profilu oraz rozróżnienie wyboru modelu od testu API.
- Zaznaczanie tekstu wiadomości, klikalne adresy URL/e-mail i kopiowanie odpowiedzi.
- Wybór pliku przez Android Storage Access Framework; tekstowe pliki kodu, logów, JSON, XML, Markdown i CSV są dołączane do następnej wiadomości.
- Dziennik zdarzeń agenta z informacją o rozpoczęciu zadania, odpowiedzi i błędzie.
- W zakładce Agenci przycisk przejścia do czatu z daną rolą.

## Ograniczenia jawnie pozostawione
- Brak wielu nazwanych konwersacji i przeszukiwalnej historii rozmów.
- Brak wysyłania obrazów/PDF/binarnych załączników do API.
- Brak pełnego renderera Markdown i podświetlania składni.
- Brak faktycznej orkiestracji równoległych agentów; dziennik nie jest transkrypcją agent-agent.
- Linki są klikalne, ale nie dodano własnego mechanizmu pobierania treści z internetu.

## Weryfikacja
- Archiwum projektu sprawdzono narzędziem `unzip -t`.
- Nie wykonano kompilacji Androida: w środowisku nie ma polecenia `gradle` ani skonfigurowanego `ANDROID_HOME`. Przed instalacją otwórz projekt w Android Studio i wykonaj Gradle Sync oraz Build.
