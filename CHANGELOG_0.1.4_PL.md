# AI Master Pro Android 0.1.4

## Zmiany
- Wiele niezależnych rozmów zapisanych lokalnie; wybór rozmowy z listy Historia.
- Nowy czat nie usuwa poprzednich rozmów.
- Możliwość usunięcia rozmowy z listy.
- Załączniki tekstowe/kod/logi oraz obrazy i PDF do 12 MB.
- Obrazy są przekazywane w formacie base64 do Gemini i endpointów OpenAI-compatible obsługujących vision; Anthropic otrzymuje blok image.
- PDF jest przekazywany jako inlineData do Gemini i jako document block do Anthropic. Endpointy OpenAI-compatible otrzymują czytelny błąd dla PDF, ponieważ nie istnieje jeden zgodny format PDF dla wszystkich takich endpointów.
- Gdy wybrany jest MASTER, wykonuje sekwencyjne wywołania agentów Badacz, Programista i Recenzent, zapisuje zdarzenia w dzienniku, a następnie scala wyniki. To nie jest równoległe wykonanie i wszystkie role używają tego samego skonfigurowanego dostawcy/modelu.
- Zwiększono wersję aplikacji do 0.1.4.

## Ograniczenia i weryfikacja
- Historia jest lokalna na urządzeniu; nie synchronizuje się między urządzeniami.
- Obsługa multimodalna zależy od możliwości konkretnego modelu/dostawcy i może być odrzucona przez endpointy nieobsługujące obrazów/dokumentów.
- Automatyczna orkiestracja jest sekwencyjna, nie równoległa; nie ma jeszcze osobnych kluczy/modeli przypisanych per agent ani wznawiania przerwanego łańcucha.
- Archiwum ZIP można sprawdzić, ale kompilacja APK wymaga Android Studio/SDK i musi zostać potwierdzona lokalnie.
