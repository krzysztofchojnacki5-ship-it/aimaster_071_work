# AI MASTER PRO — aktywny handoff

## Najnowsza baza: 0.6.136-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_136_DURABLE_WRITER_FAULT_SEAM_SOURCE.zip`.
- Pochodna zweryfikowanej paczki 0.6.135-SOURCE (SHA-256 `6eb632a71b63369c4b81b551a783f5569d517dfa1c2a535d147216a7029a6501`; `unzip -t` PASS).
- Główna zmiana: wspólny `publishWorkspaceDurableFile()` obsługuje oba produkcyjne writery. Kontroluje CREATE_NEW ownership, serializację, flush, `fd.sync()`, strict `ATOMIC_MOVE`, best-effort directory sync i cleanup. Test seams są internal/per-call, bez global hooks.
- Fault injection rzeczywistego helpera: workspace transaction harness 28/28 PASS. Pełna regresja 219 host runtime PASS / 12 harnessów; compile shim i source guards PASS.
- Cztery testy JUnit w `ProjectWorkspaceStoreTest` są ADDED / NOT RUN. Nie twierdzić, że Android/Gradle/JUnit/Robolectric wykonano.
- Android preflight BLOCKED: brak `gradle-wrapper.jar`, systemowego Gradle i Android SDK; APK nie utworzono.
- App `0.6.54 / code 80`; Companion Transport BLOCKED. Staged import nadal nie jest wdrożony; ZIP import pozostaje pod global workspace lock.
- Zachować strict `ATOMIC_MOVE`, bez fallbacku; directory fsync best-effort. Nie zmieniać AISW v1/v2, receipt format, commit point ani lock scope bez projektu migracji i fault tests.
- Następny krok 0.6.137: badać recovery po sukcesie atomic move i błędzie/pominięciu directory sync; audyt stale receipt temp po restartach i idempotencji recovery.
- Raport: `BUILD_STATUS_0.6.136_PL.md`; changelog: `CHANGELOG_0.6.136_DURABLE_WRITER_FAULT_SEAM_PL.md`; roadmap: `PROJECT_WORKSPACE_ROADMAP_PL.md`.

---

# AI MASTER PRO — aktywny handoff

## Najnowsza baza: 0.6.132-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_132_PRODUCTION_SWAP_JOURNAL_BOUNDS_SOURCE.zip`.
- Pochodna zweryfikowanej paczki 0.6.131-SOURCE (SHA-256 `4cd8b38f7e54e0c5bc5501bcd8e06f5825891ebe0ce0bad9dcaec1c2d9bac990`, `unzip -t` PASS).
- Główna zmiana produkcyjna: `ProjectWorkspaceStore.readSwapJournal()` wymaga zwykłego pliku, odrzuca symlink/path escape i sprawdza rozmiar `1..16 KiB` przed parsowaniem. AISW v1/v2 bez zmiany formatu; nadmiarowe bajty odrzucane.
- Dodano trzy testy JUnit w `ProjectWorkspaceStoreTest`: oversized journal, trailing bytes, legacy v1 recovery. Status ADDED / NOT RUN; nie zaliczać ich do PASS.
- Dodano `WORKSPACE_SWAP_JOURNAL_AUDIT_0.6.132_PL.md` i `tools/workspace_swap_journal_guard/SourceGuard.py`.
- Istniejąca hostowa regresja: 203 przypadki PASS; compile shim `-Werror` PASS; nowe source guardy PASS. Trzy JUnit testy NOT RUN.
- Produkcyjny importer nie został przeniesiony: `importZip()` nadal działa pod globalnym workspace lockiem. Prototyp journalu z 0.6.131 pozostaje odrębny; nie tworzyć drugiego źródła prawdy.
- Directory fsync jest best-effort. Nie deklarować Android power-loss durability.
- Android preflight BLOCKED: brak wrapper JAR, Gradle i Android SDK; Gradle/Robolectric/Android compile/APK/device/process-death NOT RUN.
- App `0.6.54 / code 80`; APK nie utworzono. Companion Transport BLOCKED.
- Raport: `BUILD_STATUS_0.6.132_PL.md`; changelog: `CHANGELOG_0.6.132_PRODUCTION_SWAP_JOURNAL_HARDENING_PL.md`; audyt: `WORKSPACE_SWAP_JOURNAL_AUDIT_0.6.132_PL.md`; roadmap: `PROJECT_WORKSPACE_ROADMAP_PL.md`.
- Następny krok: 0.6.133 — zaprojektować/testować bezpieczną obsługę stale temp files i crash points istniejącego AISW writer/recovery, bez zmiany formatu przed zdefiniowaniem migracji. Najpierw fault matrix i testy, potem minimalna zmiana produkcyjna.

## Poprzednia baza: 0.6.129-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_129_STAGED_IMPORT_PROTOCOL_DESIGN_SOURCE.zip`.
- Pochodna rzeczywistej paczki 0.6.128-SOURCE; przed pracą zweryfikowano SHA-256 i `unzip -t`.
- Główna praca 0.6.129: audyt `ProjectWorkspaceStore.importZip()` i MainActivity lock boundary; dodano formalny `STAGED_IMPORT_PROTOCOL_DESIGN_0.6.129_PL.md`, 12-case executable host contract model i source guard.
- Ważne: staged import NIE jest jeszcze wdrożony w produkcyjnym Kotlin. `importZip()` nadal wykonuje ekstrakcję, manifest, integrity, snapshot i swap pod `ProjectWorkspaceOperationLock`; import i `HostActiveChangeSetStore.invalidate()` pozostają w tej samej sekcji krytycznej. Nie usuwać locka ani nie przenosić ekstrakcji poza niego bez kompletnego prepare/commit/recovery.
- Kontrakt: prepare ma być side-effect-free wobec current; staging immutable i przypisany opaque jednorazowym tokenem/ownerem; commit pod global lock powtarza walidację staging tree i sprawdza aktualny workspace. Zdrowy workspace zmieniony po prepare może zostać zastąpiony, ale snapshot musi obejmować stan z chwili commit. Uszkodzony workspace = fail-closed. Replay, forged token i obcy owner odrzucone.
- Hostowa regresja: 187 przypadków PASS; compile shim `-Werror` PASS; source guards PASS. Staged import 12/12 to wyłącznie model kontraktu, nie integracja produkcyjna.
- Android preflight BLOCKED: brak `gradle-wrapper.jar`, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/Robolectric/Android compile/APK/device/process-death NOT RUN.
- App `0.6.54 / code 80`; APK nie utworzono. Companion Transport BLOCKED.
- Raport: `BUILD_STATUS_0.6.129_PL.md`; changelog: `CHANGELOG_0.6.129_STAGED_IMPORT_PROTOCOL_DESIGN_PL.md`; roadmap: `PROJECT_WORKSPACE_ROADMAP_PL.md`.
- Następny krok: 0.6.130 — zaprojektować trwałe metadane prepared stagingu i startup cleanup/recovery; wykonać fault matrix dla snapshot/journal/rename/receipt/pointer invalidation. Nie wdrażać staged importer API przed domknięciem lock-order i process-death semantics.

## Starsza baza referencyjna: 0.6.93-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_93_VERIFICATION_EVIDENCE_RESOLVER_SOURCE.zip`.
- Baza: 0.6.92-SOURCE. `VerificationEvidenceResolver` wybiera dowody wyłącznie dla exact scope task/chat/snapshot; claimy agentów pozostają oddzielne; TOOL_EXECUTION wymaga zgodnego markera startowego NOT_RUN z tym samym executionId/kind i wcześniejszym czasem.
- `ToolExecutionRegistry` zapisuje executionId także w markerach NOT_RUN (start i abandon).
- `kotlinc -Werror` PASS; lokalny shim-JUnit 48/48 PASS. Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN.
- Resolver nie jest jeszcze podłączony do MASTER synthesis. Następny etap: przekazać mu aktywny scope zadania z rzeczywistego kontekstu orkiestracji, a następnie przekazać ograniczony assessment do `MasterResponseCheck`. Nigdy nie używać fallbacku `load()`/`forTask()` przy braku scope.
- Companion transport nadal nieaktywny.

## Najnowsza baza: 0.6.91-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_91_HOST_TOOL_EXECUTION_REGISTRY_SOURCE.zip`.
- 0.6.91 dodaje host-owned ToolExecutionRegistry: executionId generuje aplikacja, NOT_RUN jest trwale zapisywany przed startem, PASS/FAIL wymaga jednorazowego uchwytu. Testy Evidence/Store/Registry 25/25 + ProjectTestRunner 3/3 = 28/28 lokalnych PASS. Adaptery narzędzi nie są jeszcze podłączone; Gradle/Android build NOT RUN. Patrz `BUILD_STATUS_0.6.91_PL.md` i `MASTER_TOOL_EXECUTION_REGISTRY_CONTRACT_PL.md`.

## Poprzednia baza: 0.6.90-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_90_EVIDENCE_SCOPE_BINDING_SOURCE.zip`.
- 0.6.90 wiąże evidence z taskId/chatId/snapshotId, dodaje exact-scope lookup i migrację ledgeru V1→V2 bez utraty legacy rekordów. ChangeSet zapisuje chatId, ProjectTestRunner nie używa już snapshotId jako taskId. Testy shim 20/20 PASS; pełny Gradle/Android build NOT RUN. Patrz `BUILD_STATUS_0.6.90_PL.md` i `MASTER_EVIDENCE_SCOPE_CONTRACT_PL.md`.

## Poprzednia baza: 0.6.89-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_89_MASTER_EVIDENCE_SELF_CHECK_SOURCE.zip`.
- 0.6.89 wzmacnia MASTER self-check: twierdzenia o testach, kompilacji i Android build wymagają odpowiednich kategorii PASS; dowód z innej kategorii nie wystarcza. Testy shim 29/29 PASS; pełny Gradle/Android build NOT RUN. Ledger nie był jeszcze automatycznie wiązany z każdym chat/task. Patrz `BUILD_STATUS_0.6.89_PL.md`.

## Poprzednia baza: 0.6.88-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_88_PERSISTENT_VERIFICATION_EVIDENCE_SOURCE.zip`.
- 0.6.88 dodaje bounded persistent evidence ledger w SharedPreferences, fail-closed decode i process-local read-modify-write monitor; ręczna weryfikacja patcha zapisuje dowody. Testy shim 21/21 PASS, Android backend stub compile PASS; Gradle/Android build NOT RUN. Ledger nie jest jeszcze konsumowany przez MASTER synthesis. Patrz `BUILD_STATUS_0.6.88_PL.md`.

## Poprzednia baza: 0.6.87-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_87_TYPED_VERIFICATION_EVIDENCE_SOURCE.zip`.
- 0.6.87 dodaje typowane dowody, które odróżniają wyniki runtime/tool od deklaracji agenta; `ProjectTestRunner.Result` zwraca evidence i nie podnosi NOT_RUN do PASS. Lokalne shim runnery: 9/9 PASS; Gradle/JUnit/Robolectric NOT RUN. Patrz `BUILD_STATUS_0.6.87_PL.md`.

## Poprzednia baza: 0.6.86-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_86_MASTER_ENGINEERING_PROTOCOL_SOURCE.zip`.
- 0.6.86 usuwa z promptów fałszywą deklarację pełnego dostępu do repozytorium; role techniczne otrzymują protokół inżynierski, a MASTER dodatkowo zasady orkiestracji/self-checku. `AgentPromptsTest`: 4/4 PASS w lokalnym shim runnerze; Gradle/JUnit/Robolectric NOT RUN. Szczegóły `BUILD_STATUS_0.6.86_PL.md` i `AI_MASTER_ENGINEERING_PLAYBOOK_PL.md`.

## Poprzednia baza: 0.6.85-SOURCE
- ZIP: `AI_MASTER_PRO_0_6_85_COMPANION_CONCURRENCY_LINEARIZATION_SOURCE.zip`.
- 0.6.85 ujednolica zakres publishera revocation z process monitor/latch; testy hostowe registry 15/15 PASS. Test integracyjny drugiej instancji store i runtime zbindowanego do pierwszej: Robolectric ADDED / NOT RUN. Szczegóły `BUILD_STATUS_0.6.85_PL.md`.
- ZIP: `AI_MASTER_PRO_0_6_84_COMPANION_IO_ADAPTERS_SOURCE.zip`.
- Baza 0.6.80: process-local latch + marker SharedPreferences + niezależny marker plikowy w `noBackupFilesDir` + best-effort usunięcie aliasu AES metadata.
- 0.6.81 usuwa kolizję stałej nazwy pliku tymczasowego: każdy writer publikuje marker z własnego pliku tymczasowego, a końcowy rename pozostaje atomowy. Test dwóch instancji/32 writerów dodany do Robolectric, ale NOT RUN.
- `wipeAllTrust()` usuwa payload i marker preferences w jednym commicie, następnie usuwa plik blokady. Latch jest resetowany dopiero po potwierdzonym usunięciu pliku.
- Gradle/JUnit/Robolectric, real Android Keystore, restart/fault injection na urządzeniu, APK/device NOT RUN. App nadal 0.6.54 / code 80; brak APK i brak transportu.
- Granica: jeśli równocześnie zawiodą oba zapisy markerów i usunięcie klucza Keystore, nie można zagwarantować trwałej blokady po restarcie. Szczegóły w `BUILD_STATUS_0.6.80_PL.md` i `BUILD_STATUS_0.6.81_PL.md`.
---

# AI MASTER PRO — przekazanie prac (stan źródeł 0.6.100-SOURCE)

Kontynuuj istniejący projekt Android `AI_MASTER_PRO_ANDROID`, nie twórz go od nowa. Zachowaj dotychczasową architekturę i numerację roadmapy. Użytkownik oczekuje samodzielnego prowadzenia kolejnych kroków, naprawiania wykrytych błędów, testów regresji, aktualizacji changelogu/statusu/roadmapy i kompletnego ZIP źródeł. Nie deklaruj buildów ani testów urządzeniowych, których faktycznie nie wykonano.

## Aktualna baza
- Najnowsza paczka źródłowa do kontynuacji: `AI_MASTER_PRO_0_6_100_PATCH_TRANSACTION_COMPENSATION_SOURCE.zip` (0.6.100-SOURCE), pochodna 0.6.99-SOURCE. Preview wiąże workspace SHA-256 i chatId; apply ponownie sprawdza oba pod lockiem; błędy przed APPLIED_UNVERIFIED uruchamiają kompensację; `ProjectWorkspaceStore` ma post-swap recovery. Resolver/lock harness 73/73 PASS; ChangeSet/HostActive compile shim PASS; workspace core `kotlinc -Werror` PASS; JVM smoke PASS; Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN. Patrz `BUILD_STATUS_0.6.100_PL.md`.
- Poprzednia baza: `AI_MASTER_PRO_0_6_99_ROLLBACK_WORKSPACE_IDENTITY_GUARD_SOURCE.zip` (0.6.99-SOURCE), pochodna 0.6.98-SOURCE. Rollback weryfikacji wymaga niezmienionej workspace identity; import + invalidation active scope atomowo serializowane. Patrz `BUILD_STATUS_0.6.99_PL.md`.
- Poprzednia baza: `AI_MASTER_PRO_0_6_98_WORKSPACE_PROCESS_LOCK_SOURCE.zip` (0.6.98-SOURCE), pochodna 0.6.97-SOURCE. Wspólny `ProjectWorkspaceOperationLock` serializuje hostowe operacje workspace. Patrz `BUILD_STATUS_0.6.98_PL.md`.
- Poprzednia baza: `AI_MASTER_PRO_0_6_97_CHANGESET_LEDGER_CORRUPTION_GUARD_SOURCE.zip` (0.6.97-SOURCE), pochodna 0.6.96-SOURCE. `ChangeSetStore.readResult()` rozróżnia VALID/CORRUPT; mutacje fail-closed, UI pokazuje korupcję. Patrz `BUILD_STATUS_0.6.97_PL.md`.
- Poprzednia baza: `AI_MASTER_PRO_0_6_96_HOST_EXPLICIT_ACTIVE_CHANGESET_SCOPE_SOURCE.zip` (0.6.96-SOURCE), pochodna 0.6.95-SOURCE. Hostowy `activeChangeSetId` jest związany z chatId i SHA-256 manifestu workspace; MASTER nie używa latest fallback. Patrz `BUILD_STATUS_0.6.96_PL.md`.
- Poprzednia baza: `AI_MASTER_PRO_0_6_95_HOST_ACTIVE_EVIDENCE_SCOPE_SOURCE.zip` (0.6.95-SOURCE), pochodna 0.6.94-SOURCE. Host wybierał pojedynczy kwalifikujący się ChangeSet, a wiele zmian dawało AMBIGUOUS. Patrz `BUILD_STATUS_0.6.95_PL.md`.
- Poprzednia baza: `AI_MASTER_PRO_0_6_88_PERSISTENT_VERIFICATION_EVIDENCE_SOURCE.zip` (0.6.88-SOURCE), pochodna 0.6.87-SOURCE. Dowody są trwałe lokalnie i zapisywane w przepływie weryfikacji patcha; MASTER synthesis jeszcze ich nie odczytuje. 21/21 testów shim PASS; pełny Android build NOT RUN. Patrz `BUILD_STATUS_0.6.88_PL.md`.
- Poprzednia baza: `AI_MASTER_PRO_0_6_87_TYPED_VERIFICATION_EVIDENCE_SOURCE.zip` (0.6.87-SOURCE), pochodna 0.6.86-SOURCE. Typowane dowody są zwracane przez `ProjectTestRunner.Result`; trwała pamięć i synteza MASTER-a jeszcze ich nie konsumują. Lokalne testy 9/9 PASS. Patrz `BUILD_STATUS_0.6.87_PL.md`.
- Poprzednia baza: `AI_MASTER_PRO_0_6_86_MASTER_ENGINEERING_PROTOCOL_SOURCE.zip` (0.6.86-SOURCE), pochodna 0.6.85-SOURCE. 0.6.86 wprowadza protokół inżynierski w `AgentPrompts` i `AI_MASTER_ENGINEERING_PLAYBOOK_PL.md`; testy promptów 4/4 PASS lokalnym shim runnerem. Patrz `BUILD_STATUS_0.6.86_PL.md`.
- Poprzednia baza Companion: `AI_MASTER_PRO_0_6_85_COMPANION_CONCURRENCY_LINEARIZATION_SOURCE.zip` (0.6.85-SOURCE), pochodna 0.6.83. Źródła aplikacji nadal `versionName=0.6.54`, `versionCode=80`; nie ma nowego APK. `CompanionProvisioningStore` używa wstrzykiwalnego `CompanionTrustIo`; wszystkie instancje w jednym procesie współdzielą monitor i fail-closed latch. Hostowy harness rzeczywistego store z atrapami Android/SharedPreferences: 3/3 PASS; fault matrix NIO 200/200 PASS; directory fsync 100/100 PASS. Źródło produkcyjne i testowe skompilowane `kotlinc -Werror` z atrapami API. Gradle/JUnit/Robolectric/Android Keystore/APK/device NOT RUN. Transport Companion pozostaje BLOCKED.
- Wersja: 0.6.54, versionCode 80. 0.6.54: przycisk limitów w Diagnostyce; logika grupowania modeli i kopiowania pojęć (bez UI). Plan: docs/UI_PLAN_MODELE_CZAT_PL.md. 0.6.53: poprawka komunikatów importu; szkic rejestru połączeń pkt 4 w drafts/pkt4 (poza buildem, NOT RUN). 0.6.52: karta Zaufanie Companion w Diagnostyce (stan + odzyskiwanie z potwierdzeniem). Parowanie bez UI do czasu transportu. NOT RUN. 0.6.51: jawne odzyskiwanie (CompanionTrustRecovery, pkt 3), naprawa importu konfiguracji. Wszystko NOT RUN. 0.6.50: CompanionPskRepository (model przejściowy, NOT RUN) oraz poprawka SecureStore (bez niszczenia klucza). 0.6.49 dodaje zapis i odczyt łańcucha publicznego z ponowną walidacją (pkt 5.1e, NOT RUN). 0.6.48 dodaje przepływ wydania leafa (CompanionTlsIssuanceFlow, pkt 5.1d) i providera Keystore (NOT RUN). 0.6.47 dodaje CompanionCsrBuilder (pkt 5.1c, DER ręczny, prototyp w docs/tools/csr_proto.py zweryfikowany openssl; Kotlin NOT RUN). 0.6.46 dodaje czysty walidator odpowiedzi CA (CompanionTlsLeafPolicy, pkt 5.1b), testy NOT RUN. Wersja 0.6.45 utwardza 0.6.44: timeout blokuje ujawnienie tożsamości TLS, zamknięcie czyści tożsamość sesji (testy nie uruchomione, patrz BUILD_STATUS_0.6.45).
- Punkt listy: 5 — trwałe provisionowanie i zaufanie Companion.
- 0.6.44 dodaje walidator zaufania do `CompanionSessionOwner`; preferowana ścieżka `CompanionProvisioningStore.createSessionOwner()` sprawdza bieżące piny z zaszyfrowanego magazynu. Aktywacja oraz kolejne operacje sesji są fail-closed po unieważnieniu/rotacji starego pinu.
- Testy TLS/Owner: 31/31 PASS, pięć powtórzeń 155/155 PASS. Wybrane klasy core + store skompilowane `kotlinc -Werror` z atrapami Android/JSON. Pełny Gradle/Robolectric i Android Keystore/device: NOT RUN.
- Ograniczenie: owner nie posiada jeszcze socketu TLS, zatem revocation jest wykrywane przy następnej operacji API ownera, nie zamyka natychmiast gniazda.

## Postęp
- Pkt 1 (projekt cyklu życia TLS): ukończony jako projekt, patrz docs/COMPANION_TLS_CERTIFICATE_LIFECYCLE_PL.md i BUILD_STATUS_0.6.45_PUNKT_5_1_PL.md. Kod bez zmian.
- Pkt 5.1b (walidator odpowiedzi CA): zrobiony w 0.6.46, NOT RUN.
- Pkt 5.1c (CSR): zrobiony w 0.6.47, NOT RUN w Kotlin, algorytm zweryfikowany prototypem.
- Pkt 5.1d (przepływ wydania): zrobiony w 0.6.48, NOT RUN w Kotlin.
- Pkt 5.1e (zapis łańcucha): zrobiony w 0.6.49, NOT RUN w Kotlin.
- Otwarte w 5.1: ochrona przed rollbackiem magazynu (licznik sprzętowy).
- Pkt 3 (recovery): zrobione w 0.6.51; UI w 0.6.52 (karta w Diagnostyce). Parowanie bez UI do czasu transportu.
- Pkt 4 (własność połączeń): registry, binding, runtime i TLS admission gate są w produkcyjnym source set od 0.6.75; lokalne testy JVM PASS, ale Gradle/JUnit/Robolectric/Android Keystore/device nadal NOT RUN. Nie uruchamiać transportu automatycznie.
- Instrukcja uruchomienia dla właściciela: docs/INSTRUKCJA_URUCHOMIENIA_W_DOMU_PL.md.
- Przegląd statyczny 0.6.52 i lista ustaleń: docs/REVIEW_0_6_52_STATIC_PL.md. Ustalenia są NOT RUN, nie PASS.
- Pkt 2 (PSK): projekt w docs/PSK_MIGRATION_DESIGN_PL.md; 0.6.50 wdraża model przejściowy (założenia w CHANGELOG_0.6.50). Pytania z projektu nadal wymagają potwierdzenia. Ustalenie: w kodzie produkcyjnym nie ma PSK do zmigrowania. Czeka na decyzje z sekcji 7 (istnienie legacy, model klucza, poprawka SecureStore).

## Następne zadania (pozostań w punkcie 5)
1. Zaprojektuj bezpieczny cykl wydania/otrzymania certyfikatu TLS z SAN, zgodny z Android Keystore i zaufanym CA; nie traktuj lokalnego certyfikatu Keystore jako certyfikatu CA-issued.
2. Zaprojektuj migrację istniejącego PSK bez logowania/eksportu sekretu, z wersjonowaniem, atomowym zapisem, rollback protection i wymuszeniem ponownego parowania przy niejednoznacznym stanie.
3. Zaimplementuj jawny recovery/re-pairing: utrata klucza lub magazynu nie może powodować automatycznego odtworzenia starego zaufania.
4. Następnie rozważ własność socketu przez owner/connection handle i natychmiastowe zamykanie aktywnych połączeń po revocation. Dodaj testy wyścigów i zamykania.
5. Uruchom pełny Gradle/Robolectric i prawdziwe testy Android Keystore, gdy dostępne będzie środowisko.

Nie rozpoczynaj punktu 6 (transport) dopóki wszystkie bramki punktu 5 nie zostaną zamknięte. Desktop Companion, OS sandbox i `PROJECT_TESTS` pozostają dalszymi etapami.

## Pochodzenie
Baza 0.6.41/0.6.42 pochodzi z rekonstrukcji 0.6.40 na bazie odzyskanego 0.6.39; nie przedstawiaj jej jako oryginalnego ZIP 0.6.40.


## Aktualizacja 0.6.75-SOURCE — produkcyjny TLS admission gate i zamykanie Runtime
- Baza: `AI_MASTER_PRO_0_6_74_COMPANION_RUNTIME_PRODUCTION_SOURCE.zip`.
- `CompanionTlsHandshakeGate` przeniesiony do `app/src/main/java`; `CompanionRuntime` posiada jedną bramkę związaną z własnym registry; `CompanionApplication.companionTlsHandshakeGate()` udostępnia ten sam obiekt.
- `CompanionConnectionRegistry.shutdown()` zamyka admission, kasuje outstanding tickets i zamyka aktywne handle. Runtime najpierw wykonuje shutdown registry, a potem odpina trust listener; błąd pierwszego kroku nie pomija drugiego.
- Test loopback mTLS obejmuje sukces, revocation po rejestracji oraz deterministyczny wyścig revocation po handshake/przed register. 40 nieudanych connect nie wycieka ticketami. Test runtime shutdown sprawdza zamknięcie aktywnych handle i odrzucenie starego ticketu.
- `kotlinc -Werror` PASS; nowy lokalny shim gate/runtime 11/11 PASS; dotychczasowy zestaw registry/binding/runtime 35/35 PASS; Application seam i manifest PASS.
- Gradle/JUnit/Robolectric/assembleDebug/APK/Android Keystore/device NOT RUN. Nie uruchamiamy transportu automatycznie. Szczegóły: `BUILD_STATUS_0.6.75_PL.md`, `CHANGELOG_0.6.75_COMPANION_TLS_GATE_PRODUCTION_PL.md`.

## Następne zadanie po 0.6.77
Przeprowadzić audyt wielu równoległych provisionów/profili oraz rotacji pinów: stary profil musi pozostać ograniczony do swojego snapshotu i zostać wycofany przy revocation; nowy profil może powstać dopiero z aktualnego snapshotu. Zbadać atomowość snapshotu pins vs revocation fence, wyścigi createSessionProvision/revokePeer/wipeAllTrust, ownership certyfikatów i bezpieczne zamykanie wielu sesji. Dodać testy JVM i integracyjne; nie uruchamiać listenera ani transportu/UI przed osobną bramką bezpieczeństwa i testami Android.

## Aktualizacja 0.6.55-SOURCE — 2026-11-05
- Baza źródeł aplikacji: 0.6.54 / versionCode 80; nie zmieniono wersji APK.
- Najnowszy ZIP roboczy: `AI_MASTER_PRO_0_6_55_PKT4_DRAFT_HARDENING_SOURCE.zip`.
- `drafts/pkt4/CompanionConnectionRegistry.kt` utwardzono statycznie: ownership identity-based, konflikt właściciela fail-closed, idempotentny duplicate registration, walidacja peerId, overflow guard, close poza lockiem.
- Lokalny `kotlinc -Werror`: PASS; custom harness szkicu 13/13 PASS (nie JUnit/Gradle); smoke 1000 wyścigów PASS. JUnit/Gradle/Android: NOT RUN.
- Rejestr nadal poza `app/src`; nie jest zintegrowany z `CompanionSessionOwner` ani `CompanionProvisioningStore`.
- Przed integracją: ticket pobierany przed handshake, wiązanie ticket.peerId z TLS peer identity, revocation listener bez I/O pod monitorami, obsługa częściowego błędu `wipeAllTrust`.
- Pełny stan paczek: `PACKAGE_REGISTRY_PL.md`; status: `BUILD_STATUS_0.6.55_PL.md`.

## Zasady kolejnego kroku
Nie podnosić wersji aplikacji ani versionCode bez zmian w kodzie aplikacji i bez walidacji. Nie deklarować pkt 4 jako ukończonego. Najpierw zebrać wyniki Android Studio właściciela.


## Aktualizacja 0.6.56-SOURCE — 2026-11-05
- Najnowszy ZIP roboczy: `AI_MASTER_PRO_0_6_56_PKT4_INTEGRATION_REVIEW_SOURCE.zip`; baza: 0.6.55-SOURCE.
- Aplikacja pozostaje `versionName=0.6.54`, `versionCode=80`; brak APK i brak zmian w `app/src`.
- Audyt statyczny wykrył ryzyka R1–R6 opisane w `docs/COMPANION_PKT4_INTEGRATION_REVIEW_0.6.56_PL.md`: ticket przed handshake, FAILED owner niezależnie od socket close, callback store poza monitorem, częściowy błąd wipe po commit, współdzielony `soTimeout` listenera, cleanup porzuconych receipt-ów.
- Nowe testy JUnit/Gradle/Android Keystore/device: NOT RUN.
- Pkt 4 OPEN; pkt 5 PARTIAL; pkt 6 BLOCKED. Następny krok: projekt małego kontraktu admission/connection handle i owner revocation signal; bez transportu.
- Rejestr paczek: `PACKAGE_REGISTRY_PL.md`; status: `BUILD_STATUS_0.6.56_PL.md`.


## Aktualizacja 0.6.57-SOURCE — 2026-11-05
- Najnowszy ZIP roboczy: `AI_MASTER_PRO_0_6_57_COMPANION_ACCEPT_CONCURRENCY_SOURCE.zip`; baza: 0.6.56-SOURCE.
- W `CompanionPinnedTlsProfile.accept()` timeout i `accept()` są serializowane per listener; handshake jest poza blokadą. `closeServer()` może przerwać oczekujące accept bez czekania na blokadę accept.
- Test JUnit loopback dodany. Kompilacja trzech klas TLS przez `kotlinc -Werror`: PASS. Niezależny harness loopback mTLS: PASS (timeout drugiego accept nie nadpisuje pierwszego). JUnit/Gradle/assembleDebug/Android Keystore/device: NOT RUN.
- Wersja aplikacji bez zmian: 0.6.54 / code 80; APK nie utworzono.
- R5 poprawione źródłowo. R1/R2/R3/R4/R6 pozostają otwarte. Pkt 4 OPEN, pkt 5 PARTIAL, pkt 6 BLOCKED.
- Szczegóły: `BUILD_STATUS_0.6.57_PL.md`, `CHANGELOG_0.6.57_COMPANION_ACCEPT_CONCURRENCY_PL.md`, `docs/COMPANION_PKT4_INTEGRATION_REVIEW_0.6.56_PL.md`.


## Aktualizacja 0.6.58-SOURCE — 2026-11-05
- Baza: 0.6.57-SOURCE; najnowszy pakiet: `AI_MASTER_PRO_0_6_58_COMPANION_PRE_HANDSHAKE_ADMISSION_SOURCE.zip`.
- Dodano szkic `drafts/pkt4/CompanionTlsHandshakeGate.kt`: ticket registry pobierany przed `profile.accept/connect`, ten sam ticket użyty po handshake do rejestracji. Revocation w trakcie handshake skutkuje stale-ticket rejection i zamknięciem socketu.
- `CompanionPinnedTlsProfile.discard(receipt)` czyści nieodebrany receipt; `OwnedConnection.close()` zamyka socket, czyści receipt i zwalnia registry.
- Test regresyjny dodany do szkicu testów registry. Gate nadal poza source set; nie jest zintegrowany z `CompanionSessionOwner`.
- Walidacja: `kotlinc -Werror` gate+registry z atrapami TLS PASS; harness JVM PASS. JUnit/Gradle/assembleDebug/Android Keystore/device NOT RUN.
- Aplikacja nadal 0.6.54 / code 80; APK nie utworzono. R1 przygotowane, lecz pkt 4 OPEN. Następny krok: R2 — terminalne powiadomienie ownera po revocation; potem R3/R4/R6. Pkt 6 BLOCKED.


## Aktualizacja 0.6.59-SOURCE — 2026-11-05
- Najnowsza paczka: `AI_MASTER_PRO_0_6_59_COMPANION_OWNER_REVOCATION_SIGNAL_SOURCE.zip`; baza: 0.6.58-SOURCE.
- Dodano `CompanionSessionOwner.invalidateForPeerRevocation()` — idempotentne terminalne FAILED i czyszczenie stanu logicznego bez zamykania socketu.
- Draft registry obsługuje `CompanionRevocationAwareConnection`; callback revocation wykonuje się poza lockiem registry. `OwnedConnection` ustawia ownera FAILED przed potencjalnie blokującym socket.close, czyści receipt i zwalnia własność.
- Gate pobiera ticket przed TLS handshake, po handshake owner konsumuje receipt/aktywuje sesję, następnie registry rejestruje wrapper. Stary ticket powoduje fail-closed.
- Walidacja: `kotlinc -Werror` core+gate PASS; owner 19/19 i registry 16/16 przez lokalne JVM harnessy/shimy (nie JUnit/Gradle). Gradle/APK/Android Keystore/device NOT RUN.
- R1/R2 przygotowane, ale registry/gate nadal poza app source set; store nie publikuje zdarzeń. Następne: R3/R4, potem R6. Pkt 4 OPEN, pkt 6 BLOCKED.


## Aktualizacja 0.6.60-SOURCE — 2026-11-05
- Najnowsza paczka: `AI_MASTER_PRO_0_6_60_COMPANION_STORE_REVOCATION_DISPATCH_SOURCE.zip`; baza 0.6.59-SOURCE.
- `CompanionProvisioningStore.revokePeer()` commit-uje pod monitorem i publikuje event po jego zwolnieniu. `wipeAllTrust()` commit-uje usunięcie pinów i próbuje usunąć alias w jednej sekcji; następnie publikuje global revoke poza monitorem nawet jeśli alias deletion failed.
- Dodano `CompanionTrustRevocationPublisher` oraz `CompanionTrustRevocationException(trustChangeCommitted=true)`. Binding do registry jest draftem `drafts/pkt4/CompanionTrustRevocationBinding.kt`.
- Walidacja: `kotlinc -Werror` store/core/gate/binding z atrapami Android/JSON PASS; publisher harness 2/2 PASS. JUnit/Gradle/Robolectric/APK/Android Keystore/device NOT RUN.
- R3/R4 przygotowane, ale binding nie jest produkcyjnie zintegrowany. R6 następny; pkt 4 OPEN, pkt 6 BLOCKED.


## Aktualizacja 0.6.61-SOURCE — 2026-11-05
- Najnowsza paczka: `AI_MASTER_PRO_0_6_61_COMPANION_TLS_RECEIPT_LIFECYCLE_SOURCE.zip`; baza 0.6.60-SOURCE.
- `CompanionPinnedTlsProfile` zapisuje socket i monotoniczny timestamp receipt-u. TTL=30s z autonomicznym cleanup przez pojedynczy daemon scheduler; purge na issuance/consume usuwa expired, zamknięte sockety i invalid SSLSession. `discard()` usuwa receipt i zamyka socket poza profile lockiem.
- Session ID jest zerowany w cleanup profilu i ownera.
- Owner/profile JVM harness: 21/21 PASS; `kotlinc -Werror` core/gate PASS. JUnit/Gradle/APK/Android Keystore/device NOT RUN.
- R1–R6 mechanicznie opracowane w szkicach/mechanizmach, ale registry/gate/binding nadal poza app source set. Następny krok: audyt end-to-end, integracyjne testy i kontrolowane wpięcie. Pkt 4 OPEN, pkt 6 BLOCKED.


## Aktualizacja 0.6.62-SOURCE — 2026-11-05
- Najnowszy ZIP: `AI_MASTER_PRO_0_6_62_COMPANION_GATE_LOOPBACK_INTEGRATION_SOURCE.zip`; baza 0.6.61-SOURCE.
- Dodano rzeczywisty loopback mTLS test ścieżki `CompanionTlsHandshakeGate` → `CompanionSessionOwner.activate` → `CompanionConnectionRegistry`. Registry revoke zamyka socket, owner przechodzi do FAILED, identity jest czyszczone.
- Owner/gate harness 22/22 PASS; `kotlinc -Werror` core/gate PASS; registry 16/16 i publisher 2/2 PASS. Nie są to JUnit/Gradle.
- Registry/gate/binding store nadal poza source set; store event publisher nie jest jeszcze używany przez aplikację. APK/Gradle/Keystore/device NOT RUN. Pkt 4 OPEN, pkt 6 BLOCKED.

## Aktualny stan po 0.6.68-SOURCE

Najnowszy szkic registry ma dwufazowe API `beginRevocation(peerId): RevocationBatch`: faza 1 zwiększa epokę i odłącza uchwyty bez I/O; faza 2 `batch.close()` zamyka je poza zewnętrznym monitorem. `revoke()` używa obu faz sekwencyjnie. Test registry 26/26 PASS w lokalnym harnessie JVM. To nadal nie jest integracja store→registry. Następny obowiązkowy krok: kontrakt pre-commit fence wywoływany przez `CompanionProvisioningStore` wewnątrz monitora przed commit, a następnie `RevocationBatch.close()` po wyjściu z monitora. Dodać testy kolejności i failure path; nie przenosić szkicu do `app/src` przed dowodem.


## Aktualizacja 0.6.69-SOURCE — pre-commit fence store→registry
- Najnowsza paczka: `AI_MASTER_PRO_0_6_69_COMPANION_STORE_FENCE_INTEGRATION_SOURCE.zip`; baza 0.6.68-SOURCE.
- `CompanionTrustRevocationFenceListener` i `CompanionTrustRevocationPublisher.beginRevocation()` wprowadzają nieblokujący kanał pre-commit. `CompanionProvisioningStore.revokePeer()` oraz `wipeAllTrust()` rozpoczynają fence pod monitorem store przed zapisem; `FenceBatch.close()` jest wykonywany po wyjściu z monitora.
- `CompanionTrustRevocationBinding` jest lifecycle-aware: rozpoczyna `registry.beginRevocation()` w fazie fence, a cleanup socketów wykonuje w zwróconym Closeable. `close()` czeka na rozpoczęte cleanup-y.
- Walidacja: store/core `kotlinc -Werror` z atrapami Android/JSON PASS; registry/publisher/binding 36/36; owner/TLS/gate 25/25; dodatkowy harness rzeczywistego `wipeAllTrust()` 1/1 PASS (fence przed commit, socket close po unlock, reentrant store read bez deadlocku, cleanup po błędzie AndroidKeyStore).
- Ważne: binding i registry/gate nadal w `drafts/pkt4`; nie ma jeszcze punktu instalacji w produkcyjnym lifecycle Companion. APK/Gradle/JUnit/Android NOT RUN; aplikacja 0.6.54/code80.
- Następny krok: znaleźć rzeczywisty lifecycle/entrypoint Companion, podłączyć binding dokładnie raz i dodać test jego tworzenia/zamykania oraz testy integracyjne Android. Nie przenosić draftów do source set przed tym audytem.

## Aktualizacja 0.6.70-SOURCE
- Dodano `drafts/pkt4/CompanionRuntime.kt` i `CompanionRuntimeTest.kt`: holder scala registry + binding, zapewnia idempotentne getOrCreate i lifecycle close. 64/64 lokalne metody harnessu JVM PASS.
- Nadal nie podłączono holdera do Android `Application`; `CompanionTrustService` nadal tworzy nowy `CompanionProvisioningStore` w konstruktorze. Następny etap: wspólny application-scoped dependency graph i testy jego inicjalizacji/odtwarzania procesu.


## Aktualizacja 0.6.71-SOURCE
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_71_COMPANION_CONCURRENT_CLOSE_HARDENING_SOURCE.zip`; baza 0.6.70-SOURCE.
- Naprawiono race lifecycle: współbieżne `close()` w `CompanionTrustRevocationBinding` i `CompanionRuntime` czekają na zakończenie pierwszego zamykania. Dodano stany OPEN/CLOSING/CLOSED, propagację zapamiętanego błędu i oczekiwanie odporne na interrupt.
- Walidacja: `kotlinc -Werror` PASS; 37/37 binding/registry/publisher + 4/4 runtime = 41/41 lokalnych testów harnessu JVM. To nie jest JUnit/Gradle.
- `Application` w manifeście nie deklaruje custom Application class. `CompanionTrustService` jest wywoływany w MainActivity w dwóch miejscach i nadal tworzy osobny `CompanionProvisioningStore` na konstruktor.
- Następny krok: zaprojektować i przetestować application-scoped graph, wprowadzić jeden store i runtime slot bez przedwczesnego przenoszenia draftów do produkcyjnego source set. Dopiero po analizie zależności i testach zdecydować o dodaniu klasy Application.
- APK/Gradle/Android Keystore/device NOT RUN; `versionName=0.6.54`, `versionCode=80`.

## Aktualizacja 0.6.72-SOURCE — Application-scoped graph
- Baza: `AI_MASTER_PRO_0_6_71_COMPANION_CONCURRENT_CLOSE_HARDENING_SOURCE.zip`.
- Dodano `drafts/pkt4/CompanionApplicationGraph.kt`: jeden lazy store i jeden runtime slot na instancję graphu; brak Context w graphie; po close runtime można odtworzyć na tym samym trwałym store, bez odtwarzania sesji.
- `CompanionTrustService` przyjmuje opcjonalnie wspólny `CompanionProvisioningStore`; MainActivity nie jest jeszcze przepięta i nadal używa konstruktora domyślnego.
- Audyt: manifest nie deklaruje custom Application; dwa call sites MainActivity (state/recover); registry/binding/runtime/graph nadal draft.
- Walidacja: `kotlinc -Werror` PASS; 3/3 testy graph PASS; Android/Gradle/JUnit/APK NOT RUN.
- Następny krok po 0.6.74: pełny Gradle/JUnit/Robolectric/assembleDebug w Android Studio; potem osobny audyt promocji TLS handshake gate i testy loopback na realnym source set. Nie uruchamiać transportu automatycznie.


## Aktualizacja 0.6.73-SOURCE — Companion Application owner
- `CompanionApplication` zarejestrowana w AndroidManifest; lazy application-scoped `CompanionProvisioningStore`.
- MainActivity oba call sites tworzą `CompanionTrustService` przez `CompanionApplication`, używając tego samego store/publishera.
- Dwa testy Robolectric dodane, ale nieuruchomione: Gradle 8.13 download zablokowany przez UnknownHostException services.gradle.org.
- Historyczny status zapisany przed 0.6.74: runtime/registry/binding były draft-only. Od 0.6.74 są produkcyjne; od 0.6.75 produkcyjny jest także TLS gate.
- APK versionName 0.6.54 / versionCode 80 bez zmian.


## Aktualizacja 0.6.76-SOURCE — spójny provisioning TLS
- Baza: `AI_MASTER_PRO_0_6_75_COMPANION_TLS_GATE_PRODUCTION_SOURCE.zip`.
- Dodano `CompanionProvisionedSession` i `CompanionProvisioningStore.createSessionProvision()`. Capability łączy jeden profil TLS i ownera utworzonego na jego bazie.
- Gate ma overloady accept/connect przyjmujące capability; również starsze overloady sprawdzają referencyjną tożsamość profilu przed utworzeniem admission ticket.
- `createSessionOwner()` oznaczono jako deprecated.
- `kotlinc -Werror` Companion core PASS; lokalny shim-JUnit gate 3/3 + runtime 6/6 = 9/9 PASS. Android store compile, Gradle/Robolectric/APK/Keystore/device NOT RUN.
- Następny krok: audyt i testy całej ścieżki store→capability→gate→OwnedConnection oraz polityki życia/rotacji profili TLS; nie uruchamiać transportu automatycznie.


## Aktualizacja 0.6.77-SOURCE — lifecycle profilu TLS i sesji
- Baza: `AI_MASTER_PRO_0_6_76_COMPANION_SESSION_PROVISION_SOURCE.zip`.
- `CompanionPinnedTlsProfile` jest teraz `Closeable`; zamknięcie jest permanentne, zamyka listener, sockety handshake/receipt/admitted, anuluje expiry i zeruje oczekujące TLS session ID.
- `CompanionTlsHandshakeGate.OwnedConnection` wiąże się z close callbackiem profilu; zamknięcie profilu zamyka ownera i zwalnia registry. Gate zabezpiecza wyścig zamknięcia przed i po rejestracji wrappera.
- `CompanionProvisionedSession.close()` zamyka ownera i jego profil. Store sprząta profil po błędzie tworzenia ownera.
- `kotlinc -Werror` Companion core PASS; Store + core z atrapami Android/JSON PASS; lokalny shim-JUnit 16/16 PASS; loopback mTLS cleanup PASS. Gradle/JUnit/Robolectric/APK/Keystore/device NOT RUN.
- Następny etap: audyt granic zaufania i lifecycle dla wielu sesji/profili, w tym rotacji pinów, zmian store w trakcie provisioningu i bezpiecznego tworzenia/wycofywania certyfikatów TLS. Punkt 6 (transport) nadal BLOCKED.


## Aktualizacja 0.6.79-SOURCE — rotacja i concurrency fail-closed
- Baza: 0.6.78-SOURCE.
- Znaleziony błąd: przy nieudanym zapisie promote/revoke/wipe stary zaszyfrowany pin mógł pozostać i akceptować nowe handshakes, mimo że istniejące sesje były odcięte.
- Naprawa: process-local latch blokuje odczyt zaufania; równolegle podejmowana jest próba usunięcia aliasu metadata AES z Android Keystore, aby pozostawiony ciphertext był nieodszyfrowywalny po restarcie. Jeżeli Keystore delete zawiedzie, nie obiecywać trwałej blokady po restarcie; suppressed exception musi pozostać widoczny. Jawny `wipeAllTrust()` resetuje latch po potwierdzonym usunięciu payloadu.
- `promotePeerRotation()` sprawdza preconditions przed fence. Dodano trzy JUnit testy współbieżności registry. Lokalny shim-JUnit 33/33 × 5, `kotlinc -Werror` PASS. Android store/Gradle/Robolectric/Keystore/APK/device NOT RUN.
- Szczegóły: `BUILD_STATUS_0.6.79_PL.md`, `CHANGELOG_0.6.79_COMPANION_ROTATION_CONCURRENCY_PL.md`.


## Aktualizacja 0.6.80-SOURCE — redundantna trwała blokada trust
- Baza: 0.6.79-SOURCE.
- Dodano niezależny plik blokady w `noBackupFilesDir` obok markeru preferences; odczyt markerów fail-closed także przy błędzie I/O.
- `wipeAllTrust()` nie resetuje latch, dopóki nie potwierdzi usunięcia danych i obu markerów.
- Wykonano `kotlinc -Werror` dla Companion core + rzeczywistego store z atrapami Android/JSON oraz fault harness 4/4 + regresja shim-JUnit 6/6. Robolectric/Gradle/Keystore/device NOT RUN.
- Następny etap: rozszerzyć failure injection o rzeczywisty Android Keystore i awarie commit/delete, zweryfikować testy Robolectric, a następnie audytować równoległe promote/revoke/wipe. Transport nadal BLOCKED.


## Aktualizacja 0.6.82-SOURCE — fsync katalogu markera
- Baza: 0.6.81-SOURCE. Po fsync pliku tymczasowego i atomowym rename wykonywany jest `FileChannel.force(true)` na katalogu nadrzędnym; analogiczna synchronizacja następuje po usunięciu markera, przed zwolnieniem process-local latch.
- Host JDK 21/Linux NIO harness: 100/100 cykli publikacji/usunięcia PASS. Test Robolectric dodany, NOT RUN. Gradle/Android Keystore/APK/device NOT RUN.
- Następny etap: dodać wstrzykiwalne fault seams dla `SharedPreferences.commit`, `Files.move`, directory fsync i Keystore deletion; testować wszystkie punkty przerwania wipe/recovery oraz wyścigi `promote/revoke/wipe`. Nie uruchamiać transportu.


## Aktualizacja 0.6.83-SOURCE — fault injection
- Baza: 0.6.82-SOURCE. `CompanionProvisioningStore` ma wewnętrzny, domyślnie pusty fault injector na granicach commit/move/fsync/delete/Keystore oraz wipe.
- Dwa testy Robolectric pokrywają granice publikacji i usuwania markera; ADDED / NOT RUN. Hostowy NIO harness 200/200 PASS, ale nie uruchamia Android store.
- To jeszcze nie jest pełny adapter I/O: wymuszenie `SharedPreferences.commit() == false` i awarii wewnątrz `KeyStore.deleteEntry()` pozostaje następnym krokiem. `BUILD_STATUS_0.6.83_PL.md` zawiera dokładne granice.
- `versionName=0.6.54`, `versionCode=80`; brak APK; transport nadal BLOCKED.


## Aktualizacja 0.6.84-SOURCE — adaptery I/O i wspólny monitor
- Baza: 0.6.83-SOURCE. Dodano `CompanionTrustIo` / `AndroidCompanionTrustIo`, aby testy mogły wymusić `SharedPreferences.commit() == false`, błąd usuwania aliasu Keystore i inne wyniki operacji bez odtwarzania ich wyłącznie przez callback fault points.
- Wspólny stan procesu per `applicationContext` zawiera monitor oraz fail-closed latch. Mutacje trust i odczyt payloadu są serializowane pomiędzy instancjami store w jednym procesie. Nie deklarujemy synchronizacji multi-process.
- Dodano trzy testy Robolectric: false commit, alias deletion failure, serializacja dwóch instancji. ADDED / NOT RUN. Gradle/Keystore/APK/device NOT RUN.
- Najnowsza baza do kontynuacji: `AI_MASTER_PRO_0_6_84_COMPANION_IO_ADAPTERS_SOURCE.zip`.

## Aktualizacja 0.6.92-SOURCE — pierwszy rzeczywisty adapter hostowy
- Baza: `AI_MASTER_PRO_0_6_91_HOST_TOOL_EXECUTION_REGISTRY_SOURCE.zip`.
- Dodano `EvidenceTrackedToolAdapter`; ręczna weryfikacja patcha `project.workspace.verify` jest opakowana przez registry. `NOT_RUN` jest utrwalany przed operacją, zwykły powrót zapisuje TOOL_EXECUTION/PASS wyłącznie jako potwierdzenie zwrócenia raportu, wyjątek zapisuje FAIL, anulowanie/przerwanie pozostawia NOT_RUN.
- Wyniki `ProjectTestRunner` są nadal osobnymi dowodami; `PROJECT_TESTS` nie może zostać zaliczony przez PASS adaptera.
- Historyczne ChangeSet bez chatId nie dostają sztucznego ID: weryfikacja działa w ścieżce legacy bez TOOL_EXECUTION.
- Lokalny shim-JUnit: 38/38 PASS; `kotlinc -Werror` PASS dla evidence/store/registry/adapter/self-check i testów. Android/Gradle NOT RUN.
- Następny krok: exact-scope `VerificationEvidenceResolver` i podłączenie do MASTER synthesis. Nie podawać MASTER-owi całego ledgeru.


## Aktualny handoff — 0.6.94-SOURCE
Baza: `AI_MASTER_PRO_0_6_94_MASTER_EVIDENCE_SYNTHESIS_INTEGRATION_SOURCE.zip`. Resolver jest podłączony do obu ścieżek MASTER w `MainActivity`; assessment jest przekazywany do `MasterResponseCheck`. `MasterEvidenceContextBuilder` wymaga jawnego identyfikatora changeSet/task/snapshot w prompt użytkownika i aktywnego chatId; brak lub wieloznaczność nie wybiera najnowszego wpisu. Lokalny harness 57/57 PASS, `kotlinc -Werror` PASS. Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN. Następny etap 0.6.95: wprowadzić host-owned aktywny evidence scope z orkiestratora, powiązany z aktualnym requestTaskId i konkretnym ChangeSet/snapshot; nie parsować scope z tekstu modelu i nie używać latest fallback. Zachować wersję aplikacji 0.6.54/code 80. Companion transport pozostaje nieaktywny.


## Kontynuacja po 0.6.95-SOURCE
- Aktualna baza: `AI_MASTER_PRO_0_6_95_HOST_ACTIVE_EVIDENCE_SCOPE_SOURCE.zip`; app version nadal 0.6.54/code 80.
- 0.6.95 dodaje `HostActiveEvidenceScopeResolver`: wybiera dokładnie jeden ChangeSet o statusie APPLIED_UNVERIFIED/APPLIED z aktywnego chatId i snapshotId; zero/multiple/malformed fail-closed. Nie wybiera najnowszego.
- `MainActivity` przekazuje host-selected scope do `MasterEvidenceContextBuilder.buildForHostScope()` w MASTER_ONLY i team synthesis.
- Lokalny harness 59/59 PASS, `kotlinc -Werror` PASS. Gradle/Robolectric/Android build/APK/device NOT RUN. Companion transport BLOCKED.
- Następny etap: jawny `activeChangeSetId` w stanie hosta/orkiestratora (ustawiany przy zatwierdzaniu/aplikowaniu patcha i resetowany przy zmianie workspace/chat); przekazywać go do MASTER bez heurystyki recency. Dodać testy lifecycle: zmiana aktywnego czatu, dwa ChangeSet, rollback, usunięcie workspace, brak/nieaktualny snapshot.
- Nie rozszerzać scope na wszystkie zmiany w czacie. Nie podnosić wersji aplikacji ani tworzyć APK bez pełnego builda Android.


## Aktualny kierunek po 0.6.97-SOURCE
1. Dodać wspólną blokadę procesu dla importu, apply, rollback i odczytów workspace; nie polegać na monitorach poszczególnych instancji `ProjectWorkspaceStore`.
2. Uruchomić nowe testy Robolectric ChangeSetStore/HostActiveChangeSetStore/workspace identity i pełny Gradle/JUnit/Robolectric, gdy środowisko będzie dostępne.
3. Nie włączać Companion transportu; pozostaje BLOCKED. Wersja aplikacji 0.6.54 / code 80 bez zmian.

## Aktualny handoff — 0.6.101-SOURCE
Baza: `AI_MASTER_PRO_0_6_101_DURABLE_WORKSPACE_SWAP_JOURNAL_SOURCE.zip`. `ProjectWorkspaceStore` zapisuje przed swapem wersjonowany `workspace.swap.journal` (UUID, hadCurrent, SHA-256 docelowego manifestu), fsyncuje plik i publikuje go atomowym rename. Recovery uznaje nowy workspace tylko po zgodności manifest hash i pełnej walidacji drzewa; w przeciwnym razie przywraca poprzednią parę. Uszkodzony journal/symlink fail-closed. Legacy recovery bez journalu zachowane. Lokalny recovery harness 4/4 PASS; workspace core `kotlinc -Werror` PASS. Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN; Companion transport BLOCKED. Następny krok: idempotentny coordinator recovery obejmujący granicę apply → snapshot ledger → APPLIED_UNVERIFIED → active pointer, z testami śmierci procesu na każdym etapie; nie wolno podnosić wersji APK ani uznawać niepotwierdzonego zapisu za sukces.

## Aktualny handoff — 0.6.102-SOURCE
Baza: `AI_MASTER_PRO_0_6_102_PATCH_LEDGER_RECOVERY_RECEIPT_SOURCE.zip`. Journal swapu v2 przenosi `changeSetId`, `chatId`, `snapshotId` i bazową workspace identity; v1 z 0.6.101 pozostaje czytelny. Receipt v3 obsługuje trwały `rollbackRequested`: recovery dokańcza restore tylko przy dokładnej identity patcha lub bazy; trzecia identity blokuje się bez nadpisania projektu. Po commit lub rollback powstaje trwały receipt, który może zostać odtworzony po śmierci procesu. `MainActivity` przy starcie weryfikuje snapshot/base identity/ledger, uzgadnia APPLIED_UNVERIFIED albo REJECTED/ROLLED_BACK i aktywuje pointer tylko przy tym samym czacie i dokładnej bieżącej workspace identity. Receipt jest ackowany dopiero po uzgodnieniu; niepewne operacje nie są oznaczane REJECTED, gdy journal/receipt nadal istnieje. Recovery harness 12/12 PASS; workspace core `kotlinc -Werror` PASS; resolver 73/73 PASS; ChangeSet compile shim PASS. Pełny Android/Gradle/Robolectric/APK/device NOT RUN. Następny etap: testy Robolectric rzeczywistego startup recovery, SharedPreferences commit failure, powtórne uruchomienie recovery (idempotencja), chat switch/import kontra recovery oraz korupcja receipt/ledger. Companion transport BLOCKED.


## Aktualny handoff — 0.6.103-SOURCE
Baza: `AI_MASTER_PRO_0_6_103_RECOVERY_POLICY_HARDENING_SOURCE.zip` (pochodna 0.6.102). Dodano czystą klasę `PatchReceiptRecoveryPolicy`, a `MainActivity.recoverPendingPatchReceiptAtStartup()` deleguje do niej decyzje rollback/snapshot/status/active scope. Testy: recovery policy 11/11 PASS; workspace transaction recovery 12/12 PASS; resolver/lock 73/73 PASS; ChangeSet/HostActive compile shim PASS; statyczna integracja MainActivity 8/8 PASS. Testy polityki są JVM, statyczna integracja nie jest Android compile. Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN. SharedPreferences commit failure/read exception i rzeczywisty Robolectric startup recovery pozostają do wykonania. Wersja aplikacji nadal 0.6.54 / code 80. Companion transport BLOCKED. Następny krok: storage seam/fake backend dla commit=false, read exception i corrupt JSON; potem Robolectric, jeżeli środowisko Gradle będzie dostępne. Nie aktywować transportu Companion.


## Aktualny handoff — 0.6.105-SOURCE
Baza: `AI_MASTER_PRO_0_6_105_HOST_LEDGER_STORAGE_SCOPE_SOURCE.zip` (pochodna 0.6.104). Audyt wykrył, że `HostActiveChangeSetStore` ignorował wstrzyknięty storage ledgeru podczas walidacji `setActive/readActive`; dodano jawny `ledgerStorage` z domyślnym adapterem do dotychczasowych SharedPreferences i testy korupcji/wyjątku odczytu. Compile shim `kotlinc -Werror` PASS; workspace recovery 12/12, policy 11/11, resolver 73/73 PASS. Dodano 2 testy Robolectric; cały ChangeSetStoreTest ma 32 metody @Test i nie był uruchamiany. Gradle/Android/APK/device NOT RUN. App version 0.6.54/code 80. Companion transport BLOCKED. Następny krok: fault matrix dla startup receipt recovery, błędy commit kolejnych mutacji, idempotencja i wyścigi import/chat switch.

## Aktualny handoff — 0.6.106-SOURCE
Baza: `AI_MASTER_PRO_0_6_105_HOST_LEDGER_STORAGE_SCOPE_SOURCE.zip`. Dodano `tools/workspace_operation_lock_harness` dla produkcyjnego `ProjectWorkspaceOperationLock`: 5/5 PASS (reentrancy, non-blocking tryLock, contention/release, 8 wątków × 150 wejść bez nakładania sekcji krytycznych, cleanup po wyjątkach). Regression: recovery policy 11/11, workspace transaction recovery 12/12, resolver/lock 73/73, ChangeSet/HostActive compile shim PASS. Pełny Gradle/JUnit/Robolectric/Android/APK/device NOT RUN. `versionName=0.6.54`, `versionCode=80`; Companion transport BLOCKED. Następny etap: wydzielić testowalny koordynator startup reconciliation z MainActivity i zbudować fake ledger/workspace backend, który pozwoli symulować awarie po każdym trwałym kroku oraz przeplot recovery/import/chat switch. Nie uznawać testów host JVM za dowód Android runtime.


## Aktualny handoff — 0.6.107-SOURCE
Baza: `AI_MASTER_PRO_0_6_106_WORKSPACE_LOCK_CONCURRENCY_HARDENING_SOURCE.zip`. Dodano `tools/patch_receipt_protocol_harness`: hostowy fault model kolejności startup receipt reconciliation, korzystający z produkcyjnej `PatchReceiptRecoveryPolicy`. Testuje crash/retry po snapshot/status/invalidation/active pointer, rollback po częściowej aktualizacji, późniejszy import (inna workspace identity), chat switch bez cross-chat activation i idempotent ack. Wyniki: protocol 8/8, policy 11/11, workspace transaction 12/12, workspace lock 5/5, resolver 73/73, ChangeSet/HostActive compile shim PASS. Ważne: nowy harness nie uruchamia MainActivity/SharedPreferences/Android process death; to JVM model protokołu, nie Robolectric. Gradle/JUnit/Robolectric/Android/APK/device NOT RUN. `versionName=0.6.54`, `versionCode=80`; Companion transport BLOCKED. Następny etap: wydzielić rzeczywisty koordynator/port recovery tak, aby testy wykonywały produkcyjną kolejność operacji przez fake backend, a następnie uruchomić Robolectric, jeśli środowisko Gradle będzie dostępne.


## Aktualny handoff — 0.6.108-SOURCE
Baza: `AI_MASTER_PRO_0_6_107_PATCH_RECEIPT_PROTOCOL_FAULT_MATRIX_SOURCE.zip` (SHA `ed13b3bf18abfc852d33a6e1a8886b69c9a093653325cde545b3a19c281cc48b`). Dodano produkcyjny `PatchReceiptRecoveryCoordinator`; `MainActivity` startup recovery deleguje do niego pod `ProjectWorkspaceOperationLock`. Koordynator ma backend port dla workspace/ledger/active scope, a harness testuje dokładnie produkcyjny kod: 8/8 PASS. Regresje: policy 11/11, protocol 8/8, workspace transaction 12/12, workspace lock 5/5, resolver 73/73, ChangeSet/HostActive compile shim PASS. Android/Gradle/Robolectric/APK/device NOT RUN; integracja MainActivity wymaga pełnego Android compile. Wersja aplikacji 0.6.54/code 80; Companion transport BLOCKED. Następny krok: testować koordynator z rzeczywistym workspace filesystem + controllable ledger backend, awarie między etapami, idempotencję po restarcie oraz import/chat switch pod tym samym lockiem.


## Aktualny handoff — 0.6.109-SOURCE
Baza: `AI_MASTER_PRO_0_6_108_PATCH_RECEIPT_COORDINATOR_SOURCE.zip` (SHA `17380e17d69803604176c75cb36d5a298729332cc082c3ca815610dd44687d2a`). Dodano `patch_receipt_filesystem_integration_harness`, który kompiluje i wykonuje produkcyjne `ProjectWorkspaceStore`, `ProjectPatchEngine`, `PatchReceiptRecoveryPolicy` i `PatchReceiptRecoveryCoordinator` na tymczasowym filesystemie. 5/5 PASS: prawdziwy import ZIP+patch+receipt i idempotentne reconcile/ack; retry po błędzie przejścia statusu; chat mismatch bez mutacji; rollback do dokładnej bazowej identity i invalidation; import ZIP czeka na zakończenie recovery pod produkcyjną blokadą. Regresje: coordinator 9/9, protocol 8/8, policy 11/11, workspace transaction 12/12, lock 5/5, resolver 73/73. Backend ledgeru pozostaje kontrolowanym adapterem JVM — to nie SharedPreferences ani Android process-death test. Gradle/JUnit/Robolectric/Android/APK/device NOT RUN. App 0.6.54/code 80; Companion transport BLOCKED. Następny etap: rzeczywisty przeplot import/recovery pod wspólnym lockiem, audyt wszystkich call sites i testy chat switch/identity race; nie aktywować Companion transportu.


## Aktualny handoff — 0.6.111-SOURCE
Baza: `AI_MASTER_PRO_0_6_110_RECOVERY_LOCK_ORDER_HARDENING_SOURCE.zip` (SHA `034f622dee30a6d4a8b9b9b9e5137f1e54ceeda0ef6acf4fcd77b9601aecd30a`). Dodano `PatchReceiptRecoveryCommitGate`; MainActivity startup recovery wykonuje chat/workspace recheck, active pointer, chat recheck/invalidation i receipt acknowledge pod jedną blokadą workspace. Zamknięto okno TOCTOU importu pomiędzy activate i ack. Testy: commit gate 5/5, concurrency 3/3, coordinator 9/9, protocol 8/8, policy 11/11, workspace lock 5/5; 41 PASS + statyczny guard MainActivity. Filesystem integration i resolver rerun TIMEOUT (brak nowych wyników); ChangeSet compile shim NOT RUN. Gradle/JUnit/Robolectric/Android/APK/device/process-death NOT RUN. App 0.6.54/code 80; Companion transport BLOCKED. Następny krok: fault injection active-pointer write/ack failure + retry/idempotence oraz Robolectric rzeczywistego HostActiveChangeSetStore i startup recovery, gdy Gradle będzie dostępny.


## Aktualny handoff — 0.6.112-SOURCE
Baza: `AI_MASTER_PRO_0_6_111_RECOVERY_ACTIVATE_ACK_ATOMICITY_SOURCE.zip`. `PatchReceiptRecoveryCommitGate` ma osobny `INVALIDATION_FAILED`; po nieudanej trwałej invalidation po chat switch nie wykonuje acknowledge. MainActivity rozróżnia activation/invalidation/ack failure. Host harness 7/7 PASS; concurrency 3/3; coordinator 9/9; protocol 8/8; policy 11/11; MainActivity static guard PASS — 38 host PASS. Dodano 3 testy Robolectric w ChangeSetStoreTest: active pointer commit failure + retry, ack failure po durable pointer + retry, invalidation failure po chat switch; ADDED / NOT RUN. Filesystem integration/workspace lock/resolver rerun TIMEOUT; Gradle/Robolectric/Android/APK/device/process-death NOT RUN. App 0.6.54/code 80; Companion transport BLOCKED. Następny krok: uruchomić ChangeSetStoreTest i startup recovery Robolectric; jeśli Gradle niedostępny, hostowy fake ledger fault injection dla commit/read/remove i retry całego koordynatora.


## Aktualny handoff — 0.6.113-SOURCE
- Baza: `AI_MASTER_PRO_0_6_112_RECOVERY_COMMIT_FAILURE_RETRY_SOURCE.zip`; nowa paczka rozszerza hostowy fault harness koordynatora, bez zmian produkcyjnego recovery.
- `patch_receipt_coordinator_harness`: 13/13 PASS; commit gate 7/7; policy 11/11; protocol 8/8. Razem 39 hostowych przypadków PASS; statyczne guardy 7/7 PASS.
- Dodano testy: ledger read exception, corrupt ledger fail-closed, retry po częściowym snapshot/status commit bez duplikowania snapshotu, retry rollbacku po błędzie backendu.
- `patch_recovery_concurrency_harness` timeout w tej iteracji; filesystem integration/workspace lock/resolver NOT RUN. Robolectric/Gradle/Android/APK/device/process-death NOT RUN.
- `versionName=0.6.54`, `versionCode=80`; bez APK. Companion transport BLOCKED.
- Następny krok: testować rzeczywiste adaptery `ChangeSetStore` i `HostActiveChangeSetStore` przez Robolectric/faulting `StringPreferenceStore`, w szczególności `read()` exception, `write()` false/exception, `remove()` false/exception, ponowne uruchomienie startup recovery i brak podwójnego efektu. Fake backend nie jest dowodem Android persistence.


## Aktualny handoff — 0.6.114-SOURCE
- Baza: `AI_MASTER_PRO_0_6_113_RECOVERY_COORDINATOR_RETRY_MATRIX_SOURCE.zip`. Dodano `tools/changeset_store_fault_harness`, który kompiluje i wykonuje rzeczywiste produkcyjne klasy `StringPreferenceStore`, `ChangeSetStore`, `HostActiveChangeSetStore`; testy korzystają z fake storage oraz shimów Android Context/JSON.
- Wynik: store fault matrix 10/10 PASS; coordinator 13/13; commit gate 7/7; policy 11/11; protocol 8/8 — łącznie 49 hostowych przypadków PASS.
- Scenariusze: read exception/corrupt JSON blokują mutacje; write false/exception nie raportuje sukcesu; active pointer write failure daje się ponowić; remove false/exception pozostaje retryable; corrupt ledger nie autoryzuje active pointer.
- Ograniczenie: to nie jest Android SharedPreferences/Robolectric. `changeset_store_compile_harness` osobno timeout, ale nowy harness z -Werror skompilował i uruchomił trzy klasy produkcyjne. Gradle/JUnit/Robolectric/Android/APK/device/process-death NOT RUN.
- `versionName=0.6.54`, `versionCode=80`; brak APK. Companion transport BLOCKED.
- Następny krok: rzeczywiste Robolectric dla AndroidStringPreferenceStore + startup recovery/process recreation, z commit false/exception/remove failure i retry/idempotencją. Nie traktować hostowych shimów jako dowodu Android runtime.


## Aktualny handoff — 0.6.115-SOURCE
- Baza: `AI_MASTER_PRO_0_6_114_CHANGESET_STORE_FAULT_MATRIX_SOURCE.zip`. Produkcyjny `PatchReceiptRecoveryCoordinator` po `recoverRequestedRollback()` sprawdza niezmienność transactionId/changeSetId/chatId/snapshotId/beforeWorkspaceIdentity/workspaceIdentity oraz wymaga `rolledBack=true` i `rollbackRequested=false`.
- Dwa nowe negatywne testy: backend nie potwierdza ukończenia rollbacku; backend zwraca receipt z podmienionym transactionId. Oba przypadki zatrzymują recovery przed snapshot verification i ledger access.
- Wyniki: coordinator 15/15; store fault 10/10; policy 11/11; protocol 8/8; concurrency 3/3; lock-order guard PASS. Razem 47 hostowych przypadków PASS + 1 statyczny guard.
- Commit gate harness TIMEOUT; nie przenosić wyniku poprzedniej iteracji jako świeżego. Robolectric/Gradle/Android/APK/device/process-death NOT RUN. Brak Android SDK i brak `gradle-wrapper.jar` w paczce.
- `versionName=0.6.54`, `versionCode=80`; brak APK. Companion transport BLOCKED.
- Następny krok: naprawić/udostępnić powtarzalne środowisko Gradle + Android SDK i uruchomić rzeczywisty Robolectric dla startup recovery/SharedPreferences; do tego czasu nie deklarować Android runtime validation.


## Aktualny handoff — 0.6.118-SOURCE
- Baza: `AI_MASTER_PRO_0_6_117_WORKSPACE_IDENTITY_RECOVERY_FENCE_SOURCE.zip`.
- Istotna poprawka: `PatchReceiptRecoveryCoordinator` wykonuje workspace identity fence przed `recoverRequestedRollback()` i ponownie po rollbacku. To usuwa możliwość wywołania rollbacku przez stary receipt w obcym workspace.
- Testy: coordinator 19/19; policy 11/11; protocol 8/8; concurrency 3/3; workspace lock 5/5; ChangeSet fault 10/10; commit gate 7/7; filesystem integration 7/7; resolver 73/73 = 136 hostowych PASS; MainActivity lock-order guard PASS.
- Coordinator i filesystem integration testują produkcyjne klasy; filesystem jest rzeczywisty tymczasowy JVM filesystem, ledger jest kontrolowanym adapterem. To nie jest Android SharedPreferences ani process-death.
- Gradle/Robolectric/Android build/APK/device NOT RUN; środowisko nadal bez wrapper JAR, systemowego Gradle i Android SDK.
- `versionName=0.6.54`, `versionCode=80`; APK nie utworzono. Companion transport BLOCKED.
- Następny etap: testy import/restore/rollback/recovery race i uzyskanie kompletnego środowiska Android do rzeczywistych testów Robolectric.

## Aktualny handoff — 0.6.120-SOURCE
Baza: `AI_MASTER_PRO_0_6_119_RECOVERY_RESTORE_RACE_HARDENING_SOURCE.zip` (SHA-256 `ebc85ba15fa823df6cccef43fb4fb17c5cfd31a6feda98e66bcacce4b7ffe639`). W 0.6.120 `HostActiveChangeSetStore.readActive()` trwale unieważnia active pointer, gdy VALID ledger jednoznacznie potwierdza brak ChangeSet lub niespójność chat/task/snapshot/status. Ledger read exception/CORRUPT nie usuwa wskaźnika, bo brak odczytu nie dowodzi jego nieaktualności. Nieudana invalidacja pozostaje jawnie INVALID. Testy hostowe: ChangeSet fault 13/13, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 5/5, resolver 73/73, workspace transaction recovery 12/12 = 162 hostowych PASS; MainActivity lock-order guard PASS. Trzy testy ChangeSetStoreTest ADDED / NOT RUN. Gradle/Robolectric/Android SharedPreferences/process death/Android build/APK/device NOT RUN; brak wrapper JAR, systemowego Gradle i Android SDK. App `0.6.54 / code 80`; Companion Transport BLOCKED. Następny krok: test semantyki SharedPreferences `commit() == false` z mutacją pamięciową i prawdziwy Robolectric startup recovery po przywróceniu środowiska Android.


## Aktualny handoff — 0.6.125-SOURCE
Baza: `AI_MASTER_PRO_0_6_124_WORKSPACE_FAILURE_RECONCILIATION_LOCK_SOURCE.zip` (SHA-256 `cebef29e22c07942166114644813f6a568eab20057b5e51eda7351d584788956`). Publiczny `ProjectTestRunner.verifyAppliedChange()` sam pobiera `ProjectWorkspaceOperationLock.withLock` i deleguje do prywatnego `verifyAppliedChangeLocked()`, dzięki czemu odczyty manifestu, snapshotu i zakresu zmian są jedną serializowaną obserwacją. MainActivity może bezpiecznie wywołać go z już trzymaną blokadą (ReentrantLock). Dodano JUnit concurrency test dla callerów zewnętrznych — ADDED / NOT RUN. Lock harness 8/8 PASS; source guard publicznej granicy runnera PASS. Regresja: ChangeSet fault 21/21, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace transaction 12/12, resolver 73/73 = 173 hostowych PASS; compile shim i MainActivity static guards PASS. Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/JUnit/Robolectric/Android/APK/device/process-death NOT RUN. App `0.6.54 / code 80`; Companion Transport BLOCKED. Następny etap 0.6.126: pełny audyt wszystkich produkcyjnych call sites `ProjectWorkspaceStore` i klasyfikacja ich jako `withLock`, `tryWithLock` lub bezpiecznie read-only; dodać automatyczny source guard dla nowych call sites. Nie przenosić odpowiedzialności na `@Synchronized` bez analizy lock-order, bo odwrócenie kolejności monitora store i globalnej blokady mogłoby wprowadzić deadlock.


## Aktualny handoff — 0.6.126-SOURCE
Baza: `AI_MASTER_PRO_0_6_125_PROJECT_TEST_RUNNER_LOCK_BOUNDARY_SOURCE.zip` (SHA-256 `abece9ca786193ce0ddffc6567989fc1554042faa5b183c29a28fc85586f71a7`). W 0.6.126 przeprowadzono audyt 39 produkcyjnych wywołań API `ProjectWorkspaceStore`: 37 jest bezpośrednio wewnątrz `ProjectWorkspaceOperationLock.withLock`/`tryWithLock`, a dwa callbacki (`currentWorkspaceIdentity`, `acknowledgePatchReceipt`) są wykonywane przez `PatchReceiptRecoveryCommitGate.commit()`, który sam pobiera globalny lock. Produkcyjny store jest konstruowany tylko w MainActivity; `ProjectTestRunner` ma własną blokadę publicznej granicy. Dodano `WorkspaceCallSiteGuard.py`, który analizuje strukturę Kotlin z pominięciem komentarzy/stringów, sprawdza wszystkie call sites, callbacki gate, centralizację konstrukcji i runner lock; uruchamiany z `workspace_operation_lock_harness/run.sh`. Brak zmian produkcyjnego Kotlin. Host regression: 173/173 PASS; ChangeSet 21/21, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem 9/9, workspace lock 8/8, transaction recovery 12/12, resolver 73/73; compile shim PASS; runner/MainActivity/new call-site guards PASS. Android preflight BLOCKED: Java 21 dostępna, brak wrapper JAR, Gradle, Android SDK, DNS `services.gradle.org` niedostępny. Gradle/JUnit/Robolectric/Android/APK/device/process-death NOT RUN. App `0.6.54 / code 80`; Companion Transport BLOCKED. Następny etap 0.6.127: audyt kolejności blokad i lifecycle/chat-switch, testy deadlock/starvation/fairness oraz dalsze poszukiwanie legalnej drogi do rzeczywistego Android build i Robolectric. Nie przenosić globalnej blokady do `@Synchronized` metod `ProjectWorkspaceStore` bez redesignu lock-order; unikać trzymania blokady podczas UI/sieci/długiego I/O.

## Aktualny handoff — 0.6.128-SOURCE
Baza: `AI_MASTER_PRO_0_6_127_CHAT_SWITCH_RECOVERY_LOCK_SERIALIZATION_SOURCE.zip` (SHA-256 `e97d25852d5ac0f167c176c2a9a56e0bccd4a5fd18c746993f9d624865f70d51`). W 0.6.128 usunięto fairness bypass w `ProjectWorkspaceOperationLock.tryWithLock()`: bezterminowe `ReentrantLock.tryLock()` może bargować mimo `ReentrantLock(true)`, dlatego zastosowano `tryLock(0L, TimeUnit.NANOSECONDS)`, które respektuje fair queue bez oczekiwania. Interrupted caller dostaje null, a flaga interrupt jest przywracana. Test runtime interrupt + source guard. Workspace lock 10/10; pełna regresja: ChangeSet 21/21, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem 9/9, workspace lock 10/10, transaction recovery 12/12, resolver 73/73 = 175 hostowych PASS; compile shim i wszystkie source guards PASS. Wszystkie harnessy uruchomiono indywidualnie po timeoutach łączonych batchy. Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/Android compile/Robolectric/SharedPreferences/APK/device/process-death NOT RUN. App `0.6.54 / code 80`; Companion Transport BLOCKED. Import ZIP nadal wykonuje ekstrakcję pod globalną blokadą; nie przenosić jej poza lock bez osobnego staged prepare/commit API, walidacji i recovery. Następny etap 0.6.129: przeanalizować staged import protocol, model ownership staging tree, walidację TOCTOU, recovery po process death i koszt trzymania locka; najpierw zaprojektować/testowalny kontrakt, nie osłabiać istniejącego atomowego importu.

## Aktualizacja 0.6.133 — verified orphan previous cleanup
- Najnowsza baza: `AI_MASTER_PRO_0_6_133_VERIFIED_ORPHAN_PREVIOUS_CLEANUP_SOURCE.zip`; SHA-256 i integralność ZIP należy zweryfikować przed kolejną pracą.
- Zmiana produkcyjna w `ProjectWorkspaceStore.cleanupInterruptedStaging()`: osierocone `.previous-*` katalogi i `.previous-*.manifest` są usuwane wyłącznie po pozytywnej weryfikacji `current/` z `current.manifest`; symlinki są odpinane bez podążania do celu.
- Nie zmieniono AISW v1/v2, writer, commit point ani staged import; `importZip()` nadal pod global workspace lock.
- Host regression: 204 PASS; workspace transaction harness 13/13 kompiluje i wykonuje rzeczywisty `ProjectWorkspaceStore.kt`; compile shim i source guards PASS. Nowy JUnit test ADDED / NOT RUN.
- Android preflight BLOCKED (brak wrapper JAR, Gradle, SDK; DNS niedostępny); Android/JUnit/Robolectric/APK/device/process-death NOT RUN. App `0.6.54 / code 80`; Companion Transport BLOCKED.
- Następny etap 0.6.134: audyt odporności `writeSwapJournal()` i `writePatchReceipt()` na pozostawione temp files, kolizję identyfikatorów, niepełny zapis oraz atomowe rename; nie zmieniać fallbacku ATOMIC_MOVE ani directory fsync bez testów platformowych. Najpierw określić bezpieczną semantykę cleanupu tempów i fault matrix.

## Aktualny handoff — 0.6.134-SOURCE
- Baza: `AI_MASTER_PRO_0_6_133_VERIFIED_ORPHAN_PREVIOUS_CLEANUP_SOURCE.zip`; SHA-256 `401349c284834ef3dc8d038e73c26b95d3b6c7726f8ce22be5737ef249c96330`; `unzip -t` PASS.
- W 0.6.134 wykryto błąd kolejności w `ProjectWorkspaceStore.cleanupInterruptedStaging()`: osierocone temp/staging files były usuwane przed pełną weryfikacją current tree. Teraz metoda najpierw weryfikuje `current/` względem manifestu i przy wyniku negatywnym natychmiast wraca, zachowując wszystkie artefakty transakcyjne.
- Dodano rzeczywiste hostowe przypadki: cleanup temp przy zweryfikowanym current; usunięcie symlink entry bez naruszenia targetu; zachowanie temp evidence przy uszkodzonym current manifest. Workspace transaction harness 15/15, pełna regresja 206 host PASS; compile shim `-Werror` i source guards PASS. JUnit regression ADDED / NOT RUN.
- Nie zmieniono AISW v1/v2, writer, commit point ani directory fsync policy. `importZip()` nadal pod global workspace lock; staged import pozostaje niezintegrowany.
- Android preflight BLOCKED (brak wrapper JAR, systemowego Gradle i Android SDK; DNS distribution niedostępny). Android/JUnit/Robolectric/APK/device/process-death NOT RUN. App `0.6.54 / code 80`; Companion Transport BLOCKED.
- Następny etap 0.6.135: przeprowadzić dokładny audyt ownership i nazw plików tymczasowych writerów swap journal/patch receipt, zachowania po nieudanym delete/rename oraz możliwości kolizji/replay. Dodać fault injection bez zmiany istniejącego commit point; nie twierdzić, że host `fsync` dowodzi Android power-loss durability.

## Aktualny handoff — 0.6.135-SOURCE
- Baza: `AI_MASTER_PRO_0_6_134_FAIL_CLOSED_TEMP_EVIDENCE_CLEANUP_SOURCE.zip`; SHA-256 `df3760fa829028dd8ecc2b3536f5593d66803dea44371c656ea8551d531ac48a`; `unzip -t` PASS.
- W 0.6.135 audyt wykrył ownership bug w `writeSwapJournal()` i `writePatchReceipt()`: bezwarunkowy `temp.delete()` w finally mógł usuwać path, którego writer nie utworzył po nieudanym `createNewFile()`. Oba writery śledzą `ownsTemp`, ustawiają je dopiero po udanym CREATE_NEW i wywołują `cleanupOwnedTemporaryFile()` wyłącznie dla własnego wpisu. Helper używa `Files.deleteIfExists`, nie dereferuje symlinka; cleanup jest best-effort.
- Host harness rzeczywiście kompiluje/uruchamia `ProjectWorkspaceStore.kt`: workspace transaction 19/19; dodano 4 scenariusze ownership (foreign collision preserve, owned temp delete, foreign symlink preserve, owned symlink unlink with target preserved). Dwa JUnit testy ADDED / NOT RUN; source guard potwierdza kolejność ownership.
- Pełna regresja 210 host PASS: ChangeSet 21, commit gate 9, coordinator 19, policy 11, protocol 8, concurrency 3, workspace lock 10, workspace transaction 19, resolver 73, filesystem integration 9, staged contract 20, staged journal prototype 8. Compile shim PASS; source guards PASS.
- Android preflight BLOCKED: Java 21 jest, ale brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/JUnit/Robolectric/Android/APK/device/process-death NOT RUN. App `0.6.54 / code 80`; Companion Transport BLOCKED.
- Nie zmieniono AISW/receipt format, commit point, ATOMIC_MOVE policy ani directory fsync policy. ZIP import nadal pod global lock; staged importer nie jest zintegrowany.
- Następny etap 0.6.136: kontynuować fault matrix rzeczywistych writerów na granicach write/flush/fd.sync/ATOMIC_MOVE/directory sync. Zaprojektować kontrolowany fault seam bez zmiany public API i bez osłabiania atomowości. Weryfikować również receipt stale-temp semantics oraz recovery po temp cleanup failure. Nie twierdzić, że host filesystem test dowodzi Android power-loss durability.
