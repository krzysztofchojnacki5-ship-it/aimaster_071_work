# AI MASTER PRO 0.6.9 — Odporność na limity dostawców

- Dodano trwały ProviderCooldownStore: stan HTTP 429 i wykładniczy cooldown (60 s → maks. 15 min) są zapisywane lokalnie i przetrwają restart aplikacji.
- Integracja obejmuje bezpośrednie wywołania MASTER-a, zadania zespołu i Peer Review.
- Profil w aktywnym cooldown jest pomijany przed wysłaniem żądania; sukces czyści licznik i blokadę.
- Nie zapisujemy promptów, odpowiedzi ani kluczy API w stanie cooldown.
- Dodano testy narastania cooldownu, czasu wygaśnięcia i resetu po sukcesie.
