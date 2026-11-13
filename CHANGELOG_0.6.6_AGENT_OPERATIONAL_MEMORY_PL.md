# AI MASTER PRO 0.6.6 — Pamięć operacyjna agentów

## Zmiany
- Dodano lokalny `AgentOperationalMemoryStore` z pamięcią przypisaną do konkretnego agenta.
- Do promptu specjalisty trafiają wyłącznie zatwierdzone, pasujące tematycznie wpisy jego własnej pamięci; limit 900 znaków i 4 wpisów.
- Wnioski z zatwierdzonych propozycji jakościowych są importowane idempotentnie również po aktualizacji aplikacji.
- Odrzucenie wcześniej zatwierdzonej propozycji usuwa jej powiązane wpisy bez usuwania podobnych wpisów pochodzących z innych zatwierdzeń.
- Dodano podgląd i ręczne usuwanie wpisów pamięci w zakładce Pamięć.
- Pamięć jest lokalna; brak dodatkowych wywołań API.
- Dodano testy zakresu agenta, trafności, limitu kontekstu, deduplikacji/proweniencji i odporności na uszkodzony JSON.

## Ograniczenia
- To pamięć kontekstowa, nie trening wag modelu.
- Automatyczne wnioski nie są zapisywane bez uprzedniej akceptacji użytkownika.
- Pełne testy Gradle i APK wymagają pobrania Gradle 8.13; bieżące środowisko nie rozwiązuje `services.gradle.org`.
