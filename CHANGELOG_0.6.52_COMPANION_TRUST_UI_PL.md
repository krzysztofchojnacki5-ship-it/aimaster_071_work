# Changelog — AI MASTER PRO 0.6.52

## Interfejs zaufania Companion (decyzja: Companion ma mieć UI)
- Nowa karta „Zaufanie Companion” w ekranie Diagnostyka (`MainActivity.addCompanionTrustCard`).
- Karta pokazuje stan zapisanego zaufania opisany zrozumiale: brak, oczekujący TLS, gotowy, wymagana naprawa z listą powodów.
- Przycisk „Odzyskaj zaufanie (ponowne parowanie)…” otwiera okno potwierdzenia. Odzyskiwanie wykonuje się tylko po wpisaniu dokładnie `PONOWNE PAROWANIE`. Anulowanie niczego nie zmienia.
- Przycisk „Odśwież stan zaufania”.
- `CompanionTrustService` (`data/`): jedyny punkt wejścia UI do stanu i odzyskiwania; nie paruje, nie przywraca starego zaufania i nie otwiera połączeń sieciowych.

## Świadomie NIE dodane
- Przycisk parowania nowego komputera. Parowanie wymaga transportu, który jest zablokowany do zamknięcia bramek punktu 5. Karta mówi o tym wprost, zamiast pokazywać przycisk, który nic nie robi.

## Granice
- Kod nie został skompilowany ani uruchomiony (NOT RUN). Karta i dialog wymagają urządzenia do weryfikacji wyglądu i zachowania.
- Odczyt stanu i odzyskiwanie na prawdziwym Keystore: NOT RUN.
