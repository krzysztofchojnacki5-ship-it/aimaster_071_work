# Build status — 0.6.86-SOURCE

- Parent: `AI_MASTER_PRO_0_6_85_COMPANION_CONCURRENCY_LINEARIZATION_SOURCE.zip`.
- App version unchanged: `versionName=0.6.54`, `versionCode=80`; no APK produced.
- Zakres wydania: pierwszy kodowy etap przeniesienia sprawdzonych metod pracy inżynierskiej do promptów agentów, ze szczególnym protokołem koordynacji MASTER-a.

## Zmiany

- `AgentPrompts` nie instruuje już agenta, że ma pełny rzeczywisty wgląd w całe repozytorium. Poprzednia deklaracja była niebezpieczna: mogła skłaniać model do udawania dostępu do plików, których nie otrzymał.
- Zastąpiono ją kontraktem dostępu opartym wyłącznie na rzeczywiście udostępnionych plikach, fragmentach, narzędziach i kontekście.
- Role techniczne MASTER, ASSISTANT, DEVELOPER, DEBUGGER i REVIEWER otrzymują zwięzły protokół: zakres i kryteria, fakty vs hipotezy, minimalny patch, testy awarii, alternatywna metoda bez fikcyjnego wyniku, rozróżnienie poziomów weryfikacji, kontrola regresji i uczciwy raport.
- MASTER otrzymuje dodatkowo zasady orkiestracji: delegowanie tylko z uzasadnieniem, minimalny kontekst dla agentów, weryfikacja konfliktów, pamięć z pochodzeniem, ostrożne uczenie z wyników i końcowy self-check.
- Dodano `AI_MASTER_ENGINEERING_PLAYBOOK_PL.md` jako trwałą specyfikację zasad. Dokument zaznacza, że prompt nie jest mechanizmem bezpieczeństwa; kolejnym etapem jest przeniesienie dowodów/statusów do typowanych struktur runtime i pamięci.
- Dodano `AgentPromptsTest.kt`.

## Walidacja wykonana

- Rzeczywiste pliki `Models.kt`, `AgentPrompts.kt` i `AgentPromptsTest.kt` skompilowane razem z lokalnym shimem JUnit przez `kotlinc -Werror`: PASS.
- Testy promptów: 4/4 PASS:
  1. brak fałszywej deklaracji pełnego dostępu do repo;
  2. MASTER otrzymuje oba protokoły;
  3. kontekst jest dołączany wyłącznie, gdy jawnie przekazany;
  4. rola WRITER nie dostaje niepotrzebnego protokołu inżynierskiego.
- Test runner jest lokalnym JVM shimem; nie jest uruchomieniem JUnit/Gradle.
- Companion source i testy z 0.6.85 nie były ponownie uruchamiane w tym etapie.

## NOT RUN / ograniczenia

- Gradle, pełny JUnit, Robolectric, `assembleDebug`, APK, Android Keystore i test na urządzeniu: NOT RUN.
- Prompt nie gwarantuje posłuszeństwa modelu; to warstwa instrukcji. Bezpieczeństwo i wiarygodność wymagają późniejszej egzekucji w kodzie runtime, kontroli narzędzi, typowanych dowodów i walidacji wyników.
- Companion transport pozostaje BLOCKED i nie został uruchomiony.
