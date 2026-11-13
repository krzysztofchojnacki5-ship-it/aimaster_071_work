# AI MASTER PRO 0.6.16 — bezpieczny podgląd patchy unified diff

## Nowe elementy
- Dodano `ProjectPatchEngine` — ograniczony parser unified diff i generator podglądu wynikowej zawartości pliku.
- Obsługiwane operacje preview: ADD, MODIFY i DELETE; jedna propozycja dotyczy jednego pliku.
- Dodano ręczny formularz „PRZYGOTUJ I ZWERYFIKUJ PATCH” w Centrum dowodzenia.
- Raport pokazuje operację, ścieżkę, bazowy i wynikowy SHA-256, liczbę linii oraz wynikową treść.

## Zabezpieczenia
- Ścisłe dopasowanie ścieżek nagłówków `---` / `+++` do ścieżki propozycji.
- Odrzucanie ścieżek absolutnych, traversal (`..`), separatorów Windows, pustych segmentów i chronionych ścieżek.
- Dla istniejących plików wymagany bazowy SHA-256 zgodny z manifestem oraz z aktualnym odczytem przez `ProjectWorkspaceStore`.
- Kontekst i linie usuwane muszą pasować dokładnie; brak fuzzy matching i przesuwania hunków.
- Walidacja liczby linii i pozycji hunków, odrzucanie nakładających się wstawek, duplikatów plików, limitów rozmiaru i liczby hunków.
- Kontrola sekretów w wynikowej zawartości przez `ChangeSafetyGuard`, rozszerzona o typowe przypisania `api_key`, `api_token`, `client_secret`, hasła i tokeny Bearer.
- Obsługa dodania/usunięcia pliku przez `/dev/null`; zachowanie znacznika braku końcowego LF.
- Silnik nie zapisuje plików, nie uruchamia kodu i nie wykonuje skryptów. To wyłącznie preview.

## Limity
- Maksymalnie 20 plików na podgląd.
- Maksymalnie 256 KB diffu na plik i 512 KB łącznie/wynikowo.
- Maksymalnie 200 hunków i 16 KB na pojedynczą linię diffu.
- Odczyt istniejącego pliku jest ograniczony do 64 KB przez `ProjectWorkspaceStore`.

## Testy i status
- 12/12 testów silnika patchy PASS w lokalnym runnerze JVM z lekkim stubem JUnit.
- Kompilacja silnika i zależności czystego Kotlin/JVM — PASS.
- Pełny Gradle/Android build i APK — NOT RUN: Wrapper próbował pobrać Gradle 8.13, ale środowisko nie rozwiązało `services.gradle.org` (UnknownHostException); Android SDK nie jest dostępne w środowisku.
- Integracja formularza UI została wprowadzona w `MainActivity.kt`, ale nie została potwierdzona kompilacją Android z powodu braku Gradle/SDK.

## Świadome ograniczenia
- Parser obsługuje jednoplikowy unified diff; brak binarnych diffów, rename/copy, trybów plików i rozszerzeń Git.
- Automatyczne przekazanie diffu z odpowiedzi Mastera/agentów do silnika nie jest jeszcze spięte.
- Nie ma jeszcze Peer Review, zatwierdzania ani zapisu. To kolejne etapy 0.6.17–0.6.19.
