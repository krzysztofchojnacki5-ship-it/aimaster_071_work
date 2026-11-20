# Pakiet 24 — zbiorcza diagnostyka trwałości danych

## Cel
Pokazać w jednym miejscu, czy kluczowe magazyny AiFORTeca są dostępne do odczytu, bez modyfikowania danych.

## Cztery zakresy zmian
1. Dodano czysty model `DiagnosticsStorageHealth`, który agreguje wyniki źródeł.
2. Dodano trzy stany całościowe: `HEALTHY`, `DEGRADED`, `UNAVAILABLE`; brak potwierdzonego odczytu nigdy nie jest uznawany za pusty magazyn.
3. Podłączono sześć rzeczywistych źródeł: kolejkę MASTER, skrzynkę przekazań agentów, kanoniczną historię rozmów, archiwum odpowiedzi MASTER-a, archiwum raportów agentów i dziennik checkpointów.
4. Dodano pięć testów regresyjnych klasyfikacji, liczników, duplikatów ID i ograniczeń wejścia.

## Ochrona danych
- Kontrola jest read-only.
- Nie wykonuje napraw, czyszczenia ani ponownych zapisów.
- Nie wyświetla treści rozmów, raportów ani payloadów przekazań.
- Błąd odczytu, uszkodzenie lub kwarantanna oznaczają źródło niedostępne; diagnostyka nie zamienia tego stanu na pusty magazyn.

## Weryfikacja
- Kod: `3f42fd7d3250cd0043e73d84b0ee84efc0993e66`.
- Commit pełnego builda: `a1652f066a1e47aaa12f68618c2f7783ea40bb87`.
- Gradle Wrapper Validation run `44674341904`: PASS.
- AiFORTeca Unit Tests run `44674341494`: PASS.
- Android Build run `44674341482`: PASS — testDebugUnitTest, assembleDebug, assembleRelease i publikacja artefaktów.
- Raport: 128 zestawów, 1354 testy, 0 awarii, 0 błędów, 0 pominiętych.

## Artefakty
| Artefakt | ID | Rozmiar |
|---|---:|---:|
| Debug APK | 14601477629 | 2 188 627 B |
| Release APK — unsigned | 14601502768 | 1 464 389 B |
| Source project | 14601482586 | 1 538 340 B |
| Test reports — Android Build | 14601621878 | 356 974 B |
| Test reports — Unit Tests | 14601436918 | 356 796 B |

SHA-256 Debug APK: `4ee8da457815d1d52dd9d5dcebce394cf9029cfe4e5b97dc98770e3b418b773c`.

Release signing was skipped on this work branch; the release APK is unsigned.

## Test ręczny
Oczekuje na użytkownika: otworzyć Diagnostykę i sprawdzić sekcję „Stan trwałych magazynów”. Potwierdzić, że wszystkie sześć źródeł ma prawidłowy status i że samo odświeżenie nie zmienia danych.

## Status pakietu
CI, testy i Android Build: PASS. Pakiet czeka na test nowej sekcji na telefonie. Pakiet 25 pozostaje wstrzymany do tej akceptacji.
