# AI MASTER PRO 0.2.1.1 — poprawka routingu batch

## Co poprawiono

- Batch agentów używa teraz jawnie wybranego wspólnego profilu modelu dla wszystkich zadań.
- Dodano wąską listę modeli Groq Free Plan opartą na oficjalnej tabeli limitów: `openai/gpt-oss-20b`, `openai/gpt-oss-120b`, `qwen/qwen3.8-27b`. Pozostałe modele Groq pozostają `UNKNOWN` i są blokowane.
- Usunięto automatyczne przekazywanie wszystkich zapisanych profili jako kandydatów failover. Wcześniej mogło to skierować zadanie do starego profilu OpenRouter, mimo że interfejs informował o wspólnym modelu Groq.
- Naprawiono rozjazd identyfikatorów: ekran AGENTY zapisuje role (`MASTER`, `ASSISTANT` itd.), natomiast katalog wykonawczy używa identyfikatorów specjalistów (`ai-engineer`, `model-routing-specialist` itd.). Batch nie próbuje już traktować tych dwóch przestrzeni nazw jako równoważnych.
- Błędy HTTP 400/401/403/404 kończą próbę routingu dla danego zadania. Nie są maskowane przez przełączanie na kolejnego dostawcę.
- Failover limitu jest rozpoznawany na podstawie HTTP 429 / kategorii `RATE_LIMIT_QUOTA`, a nie ogólnych słów typu „limit” w treści wyjątku.

## Ważne: OpenRouter 401

Odpowiedź `{"error":{"message":"User not found.","code":401}}` pochodzi od OpenRouter i wskazuje na problem autoryzacji/klucza konta. Nie jest to błąd identyfikatora modelu ani dowód wyczerpania darmowego limitu. Należy ponownie zapisać poprawny klucz API OpenRouter w zakładce MODELE i wykonać test dostawcy.

## Zakres i ograniczenia

- Nie uruchamiano Gradle ani kompilacji Androida.
- Batch działa w trybie wspólnego modelu wybranego w czacie. Osobne profile dla specjalistycznych ID z `AgentCatalog` wymagają osobnego, jawnego interfejsu przypisań; nie będą zgadywane na podstawie ról czatu.
- Nie dodano automatycznego płatnego fallbacku.
