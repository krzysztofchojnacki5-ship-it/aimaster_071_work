# AI MASTER PRO — docelowa architektura pamięci Mastera

Status: SPECYFIKACJA ARCHITEKTONICZNA — przygotowanie pod 0.7.0; nie oznacza jeszcze wdrożenia wszystkich modułów.

## Cel

Zapewnić Masterowi trwałą, kontrolowaną i możliwą do wznowienia wiedzę o użytkowniku, projektach, decyzjach oraz przebiegu pracy. Pamięć ma pomagać w doborze kontekstu, a nie zastępować aktualną weryfikację kodu, dokumentacji ani danych z API.

## Co już istnieje — nie dublować

- `AiMemoryStore`: zatwierdzone zasady/pamięć ogólna i ograniczone pobieranie kontekstu.
- `AgentOperationalMemoryStore`: lokalne, zatwierdzone wnioski przypisane do konkretnego agenta.
- `TaskCheckpointStore`: zdarzenia statusu zadań i wykrywanie przerwanych przebiegów; nie przechowuje promptów ani odpowiedzi.
- `LearningJournalStore`: metadane operacyjne, czasy, próby i tokeny; nie jest pamięcią faktów ani oceną merytoryczną.
- `QualityLearningStore`: wnioski i dane do oceny jakości agentów.

Docelowy system powinien integrować te moduły przez wspólny kontrakt i warstwę selekcji kontekstu, a nie tworzyć kolejny konkurencyjny magazyn bez migracji.

## Warstwy docelowe

1. **User Profile Memory** — stabilne preferencje i zasady współpracy. Każdy wpis ma źródło, datę, status zatwierdzenia i zakres widoczności. Użytkownik może go poprawić, wyeksportować lub usunąć.
2. **Project Memory** — osobny kontekst dla każdego projektu: identyfikator, wersje, architektura, ważne pliki, decyzje, ograniczenia, błędy i potwierdzone rozwiązania.
3. **Episodic Work Log** — chronologiczny dziennik działań Mastera: zadanie, wykonane kroki, wynik, dowody, testy PASS/FAIL/NOT_RUN i przyczyna przerwania. Nie wolno zapisywać działania jako wykonanego bez potwierdzenia.
4. **Working Memory / Task State** — bieżące zadanie, checkpoint, następny krok, blokady, aktywne pliki i bazowe hashe. Musi przetrwać restart procesu.
5. **Knowledge Retrieval** — lokalne wyszukiwanie po słowach kluczowych i metadanych; embeddings mogą być opcjonalnym dodatkiem. Każdy fragment musi zachować źródło i datę.
6. **Context Composer** — wybiera najmniejszy wystarczający kontekst pod zadanie, usuwa duplikaty, uwzględnia świeżość, zaufanie i budżet tokenów. Pamięć nie jest automatycznie wstrzykiwana w całości.
7. **Memory Governance** — edycja, usuwanie, eksport, retencja, szyfrowanie danych wrażliwych, redakcja sekretów i kontrola zgody.

## Kontrakt wpisu pamięci

Minimalne pola: `id`, `scope`, `projectId?`, `kind`, `content`, `sourceType`, `sourceRef`, `createdAt`, `updatedAt`, `confidence`, `approvalState`, `sensitivity`, `expiresAt?`, `tags`.

- `scope`: USER, PROJECT, AGENT, TASK lub GLOBAL_RULE.
- `approvalState`: PROPOSED, APPROVED, REJECTED, SUPERSEDED.
- `confidence` nie jest równoznaczne z prawdziwością; fakty wymagają źródła.
- Sekrety, klucze API, hasła, tokeny i prywatne dane uwierzytelniające nigdy nie są pamięcią kontekstową.
- Niepewne wnioski i opinie modelu pozostają PROPOSED, dopóki nie zostaną zweryfikowane lub zatwierdzone.

## Pętla uczenia i samodoskonalenia

1. Zbieraj wynik zadania i dowody (testy, diff, logi, odpowiedź użytkownika).
2. Rozdziel fakty, hipotezy i opinie.
3. Zapisuj kandydatów do pamięci jako PROPOSED.
4. Weryfikuj powtarzalność i źródło; proś o zatwierdzenie w przypadku niepewności.
5. Używaj zatwierdzonych wniosków przy podobnych zadaniach.
6. Mierz regresje i skuteczność; nie zmieniaj własnego promptu, polityk ani kodu bez kontrolowanego patcha, recenzji i jawnej zgody.

## Odporność i koszty

- Pamięć podstawowa ma działać lokalnie i offline.
- Brak sieci lub modelu nie może usuwać ani blokować dostępu do lokalnych checkpointów.
- Kontekst musi mieć twardy budżet znaków/tokenów i priorytety.
- Migracje schematu muszą być wersjonowane, atomowe i odwracalne.
- Należy obsługiwać uszkodzony rekord bez utraty całej bazy.
- Backup i eksport powinny być szyfrowane; klucze pozostają w Android Keystore.

## Kolejność wdrożenia

- **0.6.17:** dokumentacja kontraktu i inwentaryzacja istniejących magazynów.
- **0.7.0:** wspólny model pamięci, migracja istniejących danych bez utraty, projektowa pamięć i checkpoint wznowienia.
- **0.7.1:** kontekstowy retrieval z provenance i rankingiem świeżości/zaufania.
- **0.7.2:** UI zarządzania pamięcią, eksport, edycja, usuwanie i retencja.
- **0.7.3+:** opcjonalne embeddings, testy jakości pamięci i pętla uczenia z kontrolą regresji.

## Kryteria akceptacji

- Restart aplikacji nie gubi aktywnego checkpointu.
- Master odróżnia pamięć zatwierdzoną od kandydatów i nie przedstawia hipotez jako faktów.
- Każdy fakt projektowy ma odnośnik do źródła/wersji lub jawne oznaczenie jako niezweryfikowany.
- Kontekst jest ograniczony budżetem i izolowany między projektami/użytkownikami/agentami.
- Użytkownik może przeglądać, poprawiać, eksportować i usuwać wpisy.
- Testy migracji, odtwarzania, uszkodzonych danych, redakcji sekretów i offline przechodzą.
