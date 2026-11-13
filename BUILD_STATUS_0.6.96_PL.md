# BUILD STATUS — AI MASTER PRO 0.6.96-SOURCE

## Baza i zakres
- Baza: `AI_MASTER_PRO_0_6_95_HOST_ACTIVE_EVIDENCE_SCOPE_SOURCE.zip`.
- Dodano trwały `HostActiveChangeSetStore`: host przechowuje jeden jawny aktywny zakres z `chatId`, SHA-256 aktualnego manifestu workspace, `changeSetId`, `taskId`, `snapshotId` i timestampem.
- Wybór dowodów MASTER korzysta wyłącznie z dokładnego `activeChangeSetId`; brak ID = NONE, brak/duplikat/niedopasowanie/status niedozwolony = INVALID. Nie ma wyboru po czasie ani fallbacku do listy zmian.
- Aktywny zakres jest ustawiany dopiero po zastosowaniu patcha, zapisaniu snapshotu i przejściu ChangeSet do `APPLIED_UNVERIFIED`. `ApplyResult` niesie SHA-256 manifestu utworzonego przez tę operację; przed aktywacją host porównuje go z bieżącym manifestem i sprawdza, czy rozmowa źródłowa nadal jest aktywna. Aktywacja i przełączenie czatu są serializowane na monitorze Activity.
- Zmiana czatu/workspace nie może odziedziczyć starego zakresu. Rzeczywista zmiana czatu najpierw trwale czyści wskaźnik (niepowodzenie blokuje przełączenie); import workspace czyści wskaźnik po sukcesie, a odczyt mismatch także próbuje go usunąć. Workspace jest identyfikowany SHA-256 pliku `current.manifest`, więc zmiana o tej samej liczbie plików i rozmiarze nadal otrzymuje inny identyfikator.
- Rollback i odrzucenie unieważniają odpowiedni wskaźnik; dodatkowo odczyt ponownie porównuje cały tuple z bieżącym ChangeSetStore i odrzuca nieaktualny/obcy rekord.
- Zapis ChangeSetStore przełączono na `SharedPreferences.commit()` i jawny błąd przy nieudanym trwałym zapisie.
- Usunięto potencjalny lock inversion między store'ami: `ChangeSetStore.transition()` nie wywołuje `HostActiveChangeSetStore` pod swoim monitorem. Unieważnianie jest wykonywane przez ścieżki lifecycle hosta, a resolver i tak odrzuca każdy status niespełniający warunków.
- Poprawiono nieaktualne komunikaty UI/dokumentacji sugerujące, że patche nie są jeszcze zapisywane do workspace oraz że kolejny etap ma numer 0.6.19.

## Walidacja wykonana
- `bash tools/verification_resolver_harness/run.sh`: **65/65 PASS**.
- Harness kompiluje z `kotlinc -Werror` testy resolvera, buildera MASTER i regresje dokładnego wyboru `activeChangeSetId`.
- Dodano Robolectric/JUnit testy `HostActiveChangeSetStore` w `ChangeSetStoreTest` (persist/read, chat/workspace isolation, missing/corrupt pointer, rollback invalidation, wrong chat/status); dodano test SHA-256 `ProjectWorkspaceStore.currentIdentity()` dla zmiany manifestu.
- Weryfikacja statyczna call sites: MASTER_ONLY i team synthesis nadal korzystają z jednego `buildMasterEvidenceContext`; odczyt aktywnego zakresu oparty jest na host store.
- ZIP źródłowy: `AI_MASTER_PRO_0_6_96_HOST_EXPLICIT_ACTIVE_CHANGESET_SCOPE_SOURCE.zip`; rozmiar 876,429 B; `unzip -t` PASS. SHA-256 po finalizacji dokumentacji podano w raporcie paczki.

## Ograniczenia — NOT RUN
- Pełny Gradle/JUnit/Robolectric: NOT RUN.
- Nowe testy Robolectric `ChangeSetStoreTest` i `ProjectWorkspaceStoreApplyTest`: ADDED / NOT RUN.
- Android SDK build / assembleDebug / APK / urządzenie: NOT RUN.
- Testy realnego procesu restartu aplikacji i awarii zasilania: NOT RUN; `SharedPreferences.commit()` daje synchroniczny wynik zapisu, ale nie jest deklaracją testu power-loss.
- Pełna globalna serializacja importu i patch apply między różnymi instancjami `ProjectWorkspaceStore` nie została jeszcze wdrożona; kontrola manifest identity zapobiega przypisaniu wskaźnika do innej rewizji po zakończeniu apply, ale osobny audyt współbieżności workspace pozostaje otwarty.
- `versionName=0.6.54`, `versionCode=80` bez zmian; nie utworzono APK.
- Companion transport pozostaje nieaktywny/BLOCKED.

## Pozostałe ryzyka / następny krok
- `ChangeSetStore.read()` nadal ma historyczny kontrakt zwracania pustej listy po błędzie parsowania. Aktywny wskaźnik jest wtedy odrzucany jako INVALID, ale warto w kolejnym etapie dodać jawny wynik odczytu `VALID/CORRUPT`, aby UI diagnostyczne odróżniało pusty rejestr od uszkodzonego.
- Przeprowadzić pełny przegląd wszystkich ścieżek importu/usunięcia workspace i chat deletion; obecne SHA-256 manifestu oraz chatId zapewniają fail-closed przy następnym odczycie.
- Dodać Robolectric testy store i uruchomić Gradle, gdy środowisko będzie dostępne.
