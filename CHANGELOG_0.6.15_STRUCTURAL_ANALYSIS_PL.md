# AI MASTER PRO 0.6.15 — analiza strukturalna projektu

## Nowe funkcje
- Dodano lokalny `ProjectStructureAnalyzer` dla plików Kotlin (`.kt`, `.kts`) i Java (`.java`).
- Rozpoznawane deklaracje: klasy, interfejsy, obiekty, enumy, rekordy, funkcje Kotlin i metody Java.
- Skaner maskuje komentarze oraz literały tekstowe/znakowe przed rozpoznawaniem deklaracji, zachowując offsety i numery linii.
- Analiza odczytuje importy i buduje graf zależności między plikami oraz mapę zależności modułów.
- Raport wskazuje nierozwiązane importy, powielone typy, pominięte pliki i przyczyny pominięcia.
- W Centrum dowodzenia dodano ręczną akcję „Analizuj symbole i zależności”. Cięższa kontrola integralności i analiza działają w tle, a raport jest kopiowalny; nie zawiera pełnej treści analizowanych plików.

## Limity i bezpieczeństwo
- Maksymalnie 300 plików, 64 KB na plik i 4 MB łącznego odczytu na analizę.
- Analiza uruchamia się dopiero po kontroli integralności workspace.
- Każdy odczyt przechodzi przez `ProjectWorkspaceStore.readTextFile`: manifest, ścieżka kanoniczna, rozmiar, SHA-256, UTF-8 i filtr sekretów.
- Rozszerzono filtr podglądu o `api_token`, `secret_key`, `private_key` i `aws_secret_access_key`.
- Analiza jest lokalna. Nie uruchamia Gradle, skryptów ani kodu projektu i nie wysyła treści do API.

## Ograniczenia techniczne
- To ograniczony analizator strukturalny, nie pełny parser Kotlin/Java ani kompilator AST.
- Nie analizuje wywołań metod, typów generycznych w pełnej semantyce, refleksji, DI ani zależności runtime.
- Importy zewnętrzne, aliasy, importy statyczne i wildcardy mogą pozostać nierozwiązane albo zostać przypisane jedynie do pliku deklarującego typ.
- Funkcje/metody są raportowane na poziomie pakietu; nie odtwarzamy w pełni zagnieżdżenia klas i zakresów.
- Pozostałe języki są na razie poza analizą symboli.

## Testy
- 22/22 metod testowych przeszło w lokalnym runnerze kompatybilnym z używanymi asercjami JUnit.
- Smoke test na 37 rzeczywistych plikach źródłowych: 286 symboli, 116 importów, 7 krawędzi zależności, 104 importy bez dopasowania w projekcie (głównie zewnętrznych), 2 pominięte pliki.
- Dodano rozpoznawanie wildcard importów pakietów i typów oraz zoptymalizowano wyznaczanie numerów linii przez indeks nowych linii.
- Runner nie jest silnikiem JUnit/Robolectric; pełne testy Gradle i build APK wymagają dostępnego Gradle 8.13 oraz Android SDK.
