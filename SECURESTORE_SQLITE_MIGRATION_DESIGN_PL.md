# Projekt migracji SecureStore do SQLite

Status: PROJEKT — nie wdrażać przed zatwierdzeniem testów i osobnym etapem implementacji.

## 1. Zakres i niezmienniki

Migracja obejmuje wyłącznie magazyn `secure_provider_store` używany przez `SecureStore`. Nie obejmuje historii czatów, checkpointów zadań, pamięci AI, rejestru tokenów ani innych preferencji.

Niezmienniki:
- Nie zmieniać aliasu Android Keystore `ai_master_provider_key_v1`, algorytmu AES/GCM/NoPadding, sposobu pakowania IV+ciphertext ani istniejących zaszyfrowanych wartości.
- Nie zapisywać kluczy API w plaintext w SQLite, logach, wyjątkach, backupach diagnostycznych ani checkpointach.
- Nie usuwać ani nie modyfikować źródłowych SharedPreferences w trakcie migracji.
- DB staje się źródłem prawdy dopiero po atomowym zapisie danych, weryfikacji odczytu i markera migracji w jednej transakcji.
- Nie wolno tworzyć nowego klucza Keystore, jeśli istnieją stare zaszyfrowane sekrety.
- Zachować publiczny kontrakt `SecureStore` (save/read/all/replace/secret health) i schemat eksportu kopii `schemaVersion=1`.

## 2. Docelowy schemat SQLite (wersja DB 1)

Plik: `secure_provider_store.db`; implementacja na `SQLiteOpenHelper` (bez nowej zależności Room).

Tabela `provider_configs`:
- `id TEXT PRIMARY KEY NOT NULL`
- `config_json TEXT NOT NULL` — pełny bezpieczny JSON metadanych profilu, z zachowaniem nieznanych/rozszerzonych pól; przed zapisem usunięte wyłącznie `apiKey` i wewnętrzne `_secretUnavailable`.
- `parent_provider_id TEXT NULL REFERENCES provider_configs(id) DEFERRABLE INITIALLY DEFERRED` — kopia relacji do walidacji i indeksowania.
- `secret_ciphertext TEXT NULL` — istniejący format Base64(IV || AES-GCM ciphertext), nigdy plaintext.
- indeks na `parent_provider_id` (nie jest wymagany do integralności, ale przyspiesza wyszukiwanie potomków).

Nie rozbijać pozostałych pól JSON na kolumny w migracji v1. Obecny importer zachowuje dodatkowe pola JSON; schemat znormalizowany tylko do znanych kolumn utraciłby rozszerzenia i mógłby złamać kompatybilność. W przyszłości można dodać kolumny indeksujące przez osobną migrację, pozostawiając `config_json` jako dokument źródłowy.

Tabela `store_metadata`:
- `key TEXT PRIMARY KEY NOT NULL`
- `value TEXT NOT NULL`

Klucz metadata `migration_state` przyjmuje `complete_v1` wyłącznie w tej samej transakcji, w której zapisano wszystkie rekordy i zakończono kontrole. Dodatkowo przechować `source_schema=shared_preferences_v1` oraz liczbę rekordów dla diagnostyki. Bez sekretów i bez pełnych wartości profili w logach.

Relacje rodziców walidować przed transakcją: parent musi istnieć, brak self-parent i cykli. FK stanowi dodatkową ochronę integralności.

## 3. Jednorazowa migracja legacy → SQLite

1. Zablokować operacje SecureStore wspólnym lockiem.
2. Odczytać snapshot `provider_ids`, `config_<id>`, `key_<id>` ze SharedPreferences; nie modyfikować źródła.
3. Walidować identyfikatory i cały schemat profili tym samym walidatorem co obecnie. Odrzucić duplikaty, brakujące rodzice, cykle i typy niezgodne ze schematem.
4. Sprawdzić stan Keystore. Jeżeli brak aliasu przy istniejących ciphertextach, alias ma nieprawidłowy typ albo istniejący sekret nie daje się odszyfrować, przerwać migrację bez zapisu markeru i bez zmian źródła. Nie próbować generować nowego klucza.
5. Rozpocząć transakcję SQLite. Wstawić wszystkie profile i ciphertexty dokładnie w obecnym formacie. Wstawić metadata migracji. Weryfikować liczbę wierszy, unikalność ID, relacje oraz możliwość odczytu i odszyfrowania każdego niepustego sekretu przy zachowaniu starego aliasu.
6. Oznaczyć transakcję jako successful i zatwierdzić. Dopiero po commit ponownie otworzyć DB, odczytać marker i porównać liczbę rekordów. Każda niezgodność oznacza blokadę użycia DB i zachowanie legacy źródła.
7. Nie usuwać legacy SharedPreferences automatycznie. Pozostawić je jako kopię odzyskiwania przez co najmniej kilka wersji aplikacji; ewentualne czyszczenie wymaga osobnej decyzji i potwierdzonego backupu.

## 4. Odzyskiwanie po przerwaniu procesu

- Przed commit SQLite transakcja ma się wycofać; brak markera oznacza ponowienie migracji od niezmienionego źródła.
- Po commit marker i komplet danych istnieją atomowo; następne uruchomienie wybiera SQLite.
- DB bez markera nie jest źródłem prawdy. Nie łączyć automatycznie częściowego DB z legacy.
- Jeśli marker istnieje, ale kontrola integralności DB zawodzi, nie przełączać po cichu na pusty magazyn. Zablokować operacje, zachować DB i legacy oraz pokazać ścieżkę diagnostyczną/odzyskiwania.
- Nigdy nie usuwać starego aliasu Keystore w ramach migracji.

## 5. Operacje po migracji

Każdy zapis profilu, usunięcie profilu i pełny import wykonuje się w jednej transakcji SQLite. `replaceAllConfigs` najpierw waliduje i przygotowuje ciphertexty, potem wykonuje atomową wymianę rekordów. Odczyt pojedynczego profilu i listy profili nie może ujawniać sekretu, jeśli jego deszyfrowanie się nie powiedzie; zachować obecne `_secretUnavailable` i mechanizm blokady niepewnego stanu.

Błąd transakcji SQLite wymaga wycofania transakcji. Jeśli wynik commit jest niejednoznaczny albo DB nie daje się ponownie odczytać, stosować analogiczny process-wide fail-closed latch; nie kontynuować pracy na potencjalnie rozbieżnym stanie.

## 6. Testy wymagane przed wdrożeniem

- Pierwszy start pustego magazynu i pustej bazy.
- Migracja 0, 1 i wielu profili, profili z/bez sekretu, dziedziczenia rodzica.
- Zgodność ciphertextów bajt-w-bajt i odszyfrowanie starym aliasem.
- Uszkodzony JSON, nieprawidłowe typy, duplikaty ID, niedozwolone ID, brak rodzica i cykl.
- Brak aliasu przy istniejącym ciphertext, błędny typ aliasu, niedostępny sekret — brak markera, legacy bez zmian.
- Wymuszone przerwanie przed transakcją, w trakcie transakcji, po zapisaniu danych przed markerem, przy commit oraz po commit przed kontrolnym odczytem.
- Restart po każdym punkcie awarii: migracja musi być idempotentna i nigdy nie może zastąpić źródła pustym stanem.
- Atomiczność save, delete i replaceAllConfigs; ograniczenia FK i rollback.
- Backup export/import nadal używa schemaVersion 1 i jest kompatybilny w obie strony.
- Testy Robolectric dla logiki, plus testy instrumentacyjne na prawdziwym Android SQLite/Keystore przed wydaniem.

## 7. Warunki rozpoczęcia implementacji

Implementacja dopiero po:
1. potwierdzeniu CI aktualnej gałęzi;
2. dodaniu i zaliczeniu testów kontraktu obecnego SecureStore;
3. utworzeniu osobnej gałęzi/commita migracyjnego z łatwym rollbackiem;
4. przeglądzie testów crash-recovery i planu zachowania legacy źródła.

Ten dokument jest specyfikacją projektową. Nie oznacza, że migracja SQLite została zaimplementowana ani że bieżąca aplikacja już korzysta z SQLite.
