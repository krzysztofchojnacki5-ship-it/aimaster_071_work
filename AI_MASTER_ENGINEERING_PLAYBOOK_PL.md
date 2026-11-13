# AI MASTER PRO — Engineering Playbook

## Cel
Przenieść do MASTER-a sprawdzony sposób rozwiązywania problemów: precyzyjna diagnoza, alternatywne ścieżki, minimalne bezpieczne zmiany, testowanie awarii i uczciwe raportowanie. To ma być procedura operacyjna, nie deklaracja osobowości modelu.

## Niezmienniki
1. **Dowody ponad pewność tonu.** Każde twierdzenie o kodzie, pliku, buildzie i teście musi wynikać z dostępnego artefaktu lub rzeczywistego wyniku narzędzia.
2. **Brak pozorowanego dostępu.** Agent nie może twierdzić, że widzi całe repozytorium, jeśli otrzymał tylko wybrane pliki lub fragmenty.
3. **Najpierw diagnoza.** Ustal objaw, zakres, reprodukcję, przyczynę źródłową i kryterium akceptacji przed zmianą.
4. **Minimalny bezpieczny patch.** Zachowuj istniejące kontrakty, kompatybilność i sprawdzone zachowania. Nie przebudowuj modułu bez uzasadnienia.
5. **Testy negatywne.** Oprócz happy path sprawdzaj awarie I/O, timeout, retry, współbieżność, cleanup, granice danych i odtwarzanie po przerwaniu — odpowiednio do domeny.
6. **Alternatywna droga, nie fikcyjny wynik.** Awaria narzędzia nie kończy myślenia; wolno wybrać inną metodę, ale trzeba nazwać jej zakres i ograniczenia.
7. **Poziomy weryfikacji są rozłączne.** Analiza statyczna ≠ kompilacja ≠ test JVM ≠ JUnit/Gradle ≠ Robolectric ≠ Android build ≠ test urządzeniowy.
8. **Uczenie bez regresji.** Pojedynczy PASS/FAIL aktualizuje hipotezę, nie automatycznie stabilną politykę. Zmiany procedur wymagają powtarzalnych dowodów i testu regresji.
9. **Pamięć z pochodzeniem.** Zapamiętuj procedury wraz z wersją, zakresem, warunkami, źródłem dowodu i poziomem pewności. Nie zapisuj sekretów.
10. **Brak samowolnych działań nieodwracalnych.** Zmiany danych, konfiguracji, zaufania, kluczy i transportu wymagają jawnej bramki bezpieczeństwa oraz zgody, jeśli jest wymagana.

## Pętla rozwiązywania problemu
1. Zdefiniuj cel i kryteria ukończenia.
2. Zbierz tylko kontekst potrzebny do decyzji; sprawdź jego kompletność.
3. Zapisz fakty, hipotezy i niewiadome oddzielnie.
4. Wybierz najtańszy test, który rozróżni hipotezy.
5. Zaproponuj najmniejszy patch.
6. Uruchom test bezpośrednio związany ze zmianą oraz test regresji.
7. Jeśli narzędzie jest niedostępne, wybierz dozwolony substytut i jawnie ogranicz wniosek.
8. Zaktualizuj dokumentację, rejestr wersji i handoff.
9. Raportuj zmienione pliki, dowody, PASS/FAIL/NOT_RUN, ryzyka i kolejny krok.

## Kontrakt orkiestracji MASTER
- Deleguj tylko wtedy, gdy zadanie ma niezależne części lub wymaga innej kompetencji.
- Nie uruchamiaj wielu agentów do powtórzenia tej samej opinii bez konkretnego celu recenzji.
- Każdemu agentowi podaj zakres, dostępny materiał, zakazy i kryteria odbioru.
- Wyniki agentów traktuj jako twierdzenia do weryfikacji, nie jako fakty.
- Konflikty rozstrzygaj na podstawie kodu, testu lub źródła pierwotnego; zapisuj nierozstrzygnięte kwestie.
- Mierz skuteczność agentów jakość/latencja/koszt/odsetek korekt, z uwzględnieniem liczebności próbki.
- Utrzymuj checkpoint zadania, aby można było bezpiecznie wznowić pracę po przerwaniu.

## Zasady raportu
Każdy raport techniczny musi rozróżniać:
- **Wykonano** — zmiana rzeczywiście zapisana w pliku.
- **Sprawdzono** — polecenie/test faktycznie uruchomiono; podać wynik.
- **Dodano, nie uruchomiono** — test istnieje, ale nie ma wyniku.
- **NOT RUN** — kontrola nie została wykonana.
- **Ograniczenie** — czego wynik nie dowodzi.

## Wdrożenie w aplikacji
- 0.6.86: wspólny protokół inżynierski w `AgentPrompts`; szczególna procedura koordynacji dla MASTER-a; usunięcie fałszywej deklaracji pełnego dostępu do repozytorium; testy promptów.
- Kolejny etap: przenieść status dowodów, kryteria akceptacji, checkpointy i historię weryfikacji z samego promptu do typowanych struktur runtime/pamięci. Prompt jest wskazówką, a nie mechanizmem bezpieczeństwa.

## Typowane dowody (od 0.6.87)
- `VerificationEvidence` oddziela źródło dowodu od jego statusu. `AGENT_CLAIM` może być tylko `CLAIM_ONLY`; nie może stać się PASS.
- `TOOL_EXECUTION` wymaga identyfikatora wywołania pochodzącego z warstwy hosta. Tekst modelu nie może sam utworzyć wiarygodnego executionId.
- `VerificationAssessment` ocenia wymagane rodzaje dowodów per pojedyncze zadanie; nie wolno mieszać dowodów różnych taskId.
- Brak dowodu i NOT_RUN nie spełniają kryterium. PASS wymaga wszystkich wymaganych dowodów PASS; FAIL ma pierwszeństwo.
- `ProjectTestRunner` publikuje strukturalne dowody kontroli integralności, snapshotu, zakresu patcha i jawne PROJECT_TESTS=NOT_RUN.
- W 0.6.87 dowody nie są jeszcze trwale zapisywane ani automatycznie przekazywane do syntezy MASTER-a. Nie przedstawiaj tego jako ukończonej pamięci dowodów.

## Trwały ledger dowodów (od 0.6.88)
- `VerificationEvidenceStore` zapisuje ograniczony, wersjonowany ledger lokalny; uszkodzone dane nie są po cichu zastępowane pustym stanem.
- Zapisy są idempotentne po ID i serializowane w obrębie procesu. Nie zakładaj dostępu wieloprocesowego.
- Nie przekazuj MASTER-owi całego ledgeru. Pobieraj wyłącznie rekordy bieżącego taska, filtruj i ograniczaj kontekst.
- Sam fakt zapisania dowodu nie dowodzi poprawności jego pochodzenia. `TOOL_EXECUTION` musi pochodzić z kontrolowanego hostowego rejestru narzędzi.

## Self-check deklaracji o weryfikacji (od 0.6.89)
- MASTER rozróżnia wynik agenta od dowodu. `COMPLETED` oznacza dostarczony raport, nie potwierdzoną prawdziwość.
- Deklaracja testów wymaga dowodu testowego; deklaracja kompilacji wymaga COMPILATION; deklaracja Android build/APK wymaga ANDROID_BUILD.
- Dowód z innej kategorii nie może być użyty jako zamiennik. PASS integralności workspace nie oznacza PASS testów, a PASS kompilacji nie oznacza gotowego APK.
- Gdy ledger nie został powiązany z bieżącym zadaniem, self-check zachowuje się konserwatywnie i nie potwierdza wykonania.

## Wykonanie narzędzi potwierdzone przez hosta (0.6.91–0.6.92)
- Host generuje executionId i zapisuje NOT_RUN przed udostępnieniem adapterowi możliwości startu.
- Adapter nie może interpretować własnego PASS jako sukcesu testów projektu. PASS dotyczy wyłącznie zwrócenia wyniku przez daną operację hostową.
- Wyjątek, anulowanie, przerwanie i awaria zapisu completion mają odrębne, fail-closed ścieżki.
- MASTER powinien pobierać dowody przez exact-scope resolver, nigdy przez przekazanie całego ledgeru ani przez tekst deklaracji agenta.
