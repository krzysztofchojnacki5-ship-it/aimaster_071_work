# AI MASTER PRO 0.2.1 — katalog modeli i profile agentów

## Zakładka MODELE
- Przebudowano ją w centrum zarządzania dostawcami i katalogiem modeli.
- Dodano pobieranie modeli z API wszystkich skonfigurowanych dostawców.
- Dodano automatyczną synchronizację katalogu maksymalnie raz na 6 godzin oraz ręczne odświeżanie.
- Dodano wyszukiwarkę modeli, kopiowanie ID, test konkretnego modelu i tworzenie osobnego profilu modelu.
- OpenRouter: katalog odczytuje pola `pricing.prompt` i `pricing.completion`; model jest oznaczony jako darmowy tylko wtedy, gdy metadane cenowe wskazują zero albo jawny identyfikator `:free` bez metadanych cenowych.
- Pozostali dostawcy bez metadanych cenowych otrzymują status „CENA NIEZNANA”, a nie „FREE”.
- Zapisane profile modeli dziedziczą URL, typ dostawcy i klucz z profilu bazowego. Klucz nie jest kopiowany do każdego profilu modelu.
- Usunięto poleganie na starych, zaszytych na stałe identyfikatorach modeli w profilach domyślnych.

## Zakładka AGENCI
- Usunięto z kart agentów powielony katalog modeli, rekomendacje i dodawanie endpointów.
- Agenci wybierają gotowe profile modeli z MODELE, mają osobne przypisania i test połączenia.

## Bezpieczeństwo i failover
- Automatyczny failover dopuszcza wyłącznie modele z potwierdzonym zerowym cennikiem OpenRouter, jawne modele `:free` lub lokalny Ollama.
- Błąd 401/403 i pozostałe błędy klienta nie uruchamiają automatycznego przełączenia na inny model. Najpierw trzeba naprawić autoryzację/konfigurację.
- Klucz API jest przy zapisie przycinany z białych znaków.
- Test modelu pokazuje kod HTTP i odpowiedź błędu dostawcy, jeśli jest dostępna.

## Weryfikacja
- Nie uruchamiano Gradle ani kompilacji Androida zgodnie z ustaleniem — kompilację wykonuje użytkownik w Android Studio.
- Nie wykonywano rzeczywistych żądań do API; wynik testu konkretnego modelu zależy od kluczy i kont użytkownika.
