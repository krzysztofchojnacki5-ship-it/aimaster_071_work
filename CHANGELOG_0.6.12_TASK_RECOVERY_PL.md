# AI MASTER PRO 0.6.12 — Odporność na przerwane zadania

- Dodano TaskCheckpointStore zapisujący stany QUEUED/RUNNING/COMPLETED/FAILED/TIMED_OUT/CANCELLED/INTERRUPTED.
- TeamRunEngine emituje checkpointy przy zmianach stanu; dane są lokalne i nie zawierają promptów, odpowiedzi ani kluczy API.
- Przy starcie aplikacji pozostawione RUNNING są oznaczane jako INTERRUPTED.
- Command Center pokazuje liczbę checkpointów i pozwala skopiować listę przerwanych/nieudanych zadań do diagnostyki.
- Ograniczenie: aplikacja nie odtwarza automatycznie promptu po restarcie, ponieważ nie przechowuje wrażliwej treści zadań. Zadanie można bezpiecznie ponowić z oryginalnej rozmowy.
