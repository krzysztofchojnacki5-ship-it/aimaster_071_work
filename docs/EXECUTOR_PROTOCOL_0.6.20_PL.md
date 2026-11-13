# Specyfikacja protokołu executora — 0.6.20

## Przepływ docelowy

1. Aplikacja weryfikuje aktywny workspace i wybrany snapshot.
2. Lokalny eksporter tworzy bundle z allowlisty plików, filtruje ścieżki i sekrety, zapisuje SHA-256 plików oraz deterministyczny manifest.
3. Companion otrzymuje wyłącznie identyfikator zadania z zamkniętej listy i politykę zasobów. Nie otrzymuje arbitralnego polecenia shell.
4. Companion weryfikuje manifest, rozmiary, SHA-256 i skan sekretów niezależnie.
5. Companion uruchamia kontrolowany runtime wewnątrz OS-level sandboxa. Sieć jest wyłączona; limity CPU/RAM/czasu/procesów i dostęp do plików muszą być egzekwowane przez system, a nie tylko zapisane w JSON.
6. Po zakończeniu companion tworzy receipt obejmujący `runId`, digest bundle, task, wersję executora, czasy, exit code, wynik, potwierdzone limity, stan sandboxa, stan sieci i SHA-256 logu.
7. Receipt jest podpisany kluczem prywatnym executora. Aplikacja weryfikuje podpis wyłącznie przypiętym kluczem publicznym oraz pełną zgodność z wcześniej wysłanym requestem.
8. Dopiero przyszła, osobno audytowana integracja może przekazać zweryfikowany receipt do wewnętrznej bramki `PROJECT_TESTS`. Nie dodawać publicznego API przyjmującego sam tekst PASS.

## Typy zadań

- `UNIT_TEST_DEBUG` → kontrolowane zadanie Gradle `testDebugUnitTest`.
- `COMPILE_DEBUG` → kontrolowane zadanie Gradle `compileDebugKotlin`.

Lista jest zamknięta. Rozszerzenie wymaga zmiany wersji/kontraktu i testów. Żadnych parametrów shell, własnych tasków z UI, ścieżek do programów ani skryptów użytkownika.

## Limity protokołu

| Zasób | Maksimum |
|---|---:|
| Liczba plików | 3 000 |
| Pojedynczy plik | 8 MiB |
| Bundle (suma plików) | 40 MiB |
| Czas | 15 min |
| RAM | 2 048 MiB |
| CPU | 4 rdzenie |
| Sieć | wyłączona |

Wartości są górną granicą kontraktu. Executor może zastosować limity niższe. Musi raportować faktycznie zastosowane wartości.

## Format podpisu receipt

Podpis obejmuje deterministyczny payload UTF-8 z pól `ExecutionReceipt` z wyłączeniem samego `signatureBase64`, rozdzielonych znakiem LF, w kolejności z `ExecutorProtocol.canonicalReceipt()`. Algorytm: `SHA256withRSA`; klucz publiczny musi być przypięty przez aplikację i mieć co najmniej 2048 bitów. Klucz dołączony do bundle/receipt nie jest zaufany.

## Ograniczenia implementacji 0.6.20

To kontrakt i walidator, nie gotowy transport ani sandbox. Nie ma jeszcze archiwizatora bundle, procesu desktopowego, kanału IPC, provisioningu klucza ani ścieżki zapisu `PROJECT_TESTS`. Deklaracja `sandboxVerified` jest akceptowana tylko jako element podpisanego receipt; zaufanie do jej prawdziwości wymaga audytu i testów companion executora. Do czasu jego wdrożenia `PROJECT_TESTS` musi pozostać `NOT_RUN`.
