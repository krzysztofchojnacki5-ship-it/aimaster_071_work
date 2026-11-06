# AI MASTER PRO — rejestr paczek

Rejestr rozróżnia paczkę źródłową od wersji aplikacji/APK. Numer paczki nie oznacza automatycznie, że istnieje APK o takim samym numerze.

| Identyfikator | Nazwa paczki / pliku | Wersja aplikacji | versionCode | Pochodzenie | Status walidacji |
|---|---|---:|---:|---|---|
| 0.6.54 | `AI_MASTER_PRO_0_6_54_UI_LIMITS_GROUPING_SOURCE.zip` | 0.6.54 | 80 | Paczka źródłowa przekazana 2026-11-05 | Gradle/JUnit NOT RUN; zmiany opisane w `BUILD_STATUS_0.6.54_PL.md` |
| 0.6.55-SOURCE | `AI_MASTER_PRO_0_6_55_PKT4_DRAFT_HARDENING_SOURCE.zip` | 0.6.54 | 80 | Pochodna źródeł 0.6.54; zmiany wyłącznie w szkicu pkt 4 i dokumentacji | `kotlinc -Werror` PASS; custom harness 13/13 PASS; smoke 1000 wyścigów PASS; JUnit/Gradle/Android NOT RUN |
| 0.6.56-SOURCE | `AI_MASTER_PRO_0_6_56_PKT4_INTEGRATION_REVIEW_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.55-SOURCE; audyt statyczny czterech komponentów Companion, kontrakt integracji i ryzyka R1–R6; bez zmian produkcyjnego kodu | Audyt statyczny PASS; nowe JUnit/Gradle/APK/Android Keystore/device NOT RUN |
| 0.6.57-SOURCE | `AI_MASTER_PRO_0_6_57_COMPANION_ACCEPT_CONCURRENCY_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.56-SOURCE; poprawka współbieżności `SSLServerSocket.soTimeout` w `CompanionPinnedTlsProfile.accept()`, test JUnit loopback dodany | `kotlinc -Werror` PASS; niezależny loopback mTLS harness PASS; JUnit/Gradle/APK/Android Keystore/device NOT RUN |
| 0.6.58-SOURCE | `AI_MASTER_PRO_0_6_58_COMPANION_PRE_HANDSHAKE_ADMISSION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.57-SOURCE; szkic `CompanionTlsHandshakeGate`, ticket przed handshake, odrzucanie starych połączeń i cleanup receipt | `kotlinc -Werror` gate+registry z atrapami TLS PASS; harness JVM PASS; JUnit/Gradle/APK/Android Keystore/device NOT RUN |
| 0.6.59-SOURCE | `AI_MASTER_PRO_0_6_59_COMPANION_OWNER_REVOCATION_SIGNAL_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.58-SOURCE; owner terminalnie FAILED po revocation, revocation-aware handle i aktywacja ownera przed rejestracją | `kotlinc -Werror` core+gate PASS; owner harness 19/19 PASS; registry shim 16/16 PASS; JUnit/Gradle/APK/Android Keystore/device NOT RUN |
| 0.6.60-SOURCE | `AI_MASTER_PRO_0_6_60_COMPANION_STORE_REVOCATION_DISPATCH_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.59-SOURCE; store emituje zdarzenia po commit poza monitorem; wipe próbuje usunąć alias, ale publikuje global revoke także przy błędzie; binding nadal draft | `kotlinc -Werror` store/core/gate z atrapami Android/JSON PASS; publisher 2/2 PASS; Gradle/Robolectric/Keystore/device NOT RUN |
| 0.6.61-SOURCE | `AI_MASTER_PRO_0_6_61_COMPANION_TLS_RECEIPT_LIFECYCLE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.60-SOURCE; TTL 30 s, cleanup socketu/receipt, purge zamkniętych/nieważnych sesji, zerowanie TLS session ID | `kotlinc -Werror` core/gate PASS; owner harness 21/21 PASS; JUnit/Gradle/APK/Android Keystore/device NOT RUN |
| 0.6.62-SOURCE | `AI_MASTER_PRO_0_6_62_COMPANION_GATE_LOOPBACK_INTEGRATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.61-SOURCE; test rzeczywistego gate→owner→registry na loopback mTLS; revocation zamyka socket i ustawia owner FAILED | `kotlinc -Werror` core/gate PASS; owner/gate loopback harness 22/22 PASS; registry 16/16, publisher 2/2 PASS; JUnit/Gradle/APK/Android Keystore/device NOT RUN |
| 0.6.63-SOURCE | `AI_MASTER_PRO_0_6_63_COMPANION_SINGLE_USE_ADMISSION_TICKETS_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.62-SOURCE; jednorazowe admission tickets, limit 32 oczekujących handshake, jawne cancel w finally, testy replay i dokumentacja kolejności wipe | `kotlinc -Werror` registry/gate PASS; registry 19/19, owner/gate loopback 22/22, publisher 2/2 lokalne harnessy; JUnit/Gradle/APK/Android Keystore/device NOT RUN |

| 0.6.64-SOURCE | `AI_MASTER_PRO_0_6_64_COMPANION_REVOCATION_RACE_HARDENING_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.63-SOURCE; poprawka wyścigu: stary ticket nie może wywłaszczyć uchwytu innego peera; prunowanie osieroconych epok; cleanup gate także po wyjątku ownera | `kotlinc -Werror` registry/gate/core PASS; registry 23/23 lokalny shim; Gradle/JUnit/APK/Android Keystore/device NOT RUN |
| 0.6.65-SOURCE | `AI_MASTER_PRO_0_6_65_COMPANION_REVOCATION_BINDING_LIFECYCLE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.64-SOURCE; lifecycle-safe binding, interfejs źródła zdarzeń, close czeka na callback | `kotlinc -Werror` PASS; binding 2/2 lokalny JVM harness; Gradle/JUnit/APK/Android NOT RUN |
| 0.6.66-SOURCE | `AI_MASTER_PRO_0_6_66_COMPANION_CLOSE_RACE_FATAL_DISPATCH_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.65-SOURCE; rezerwacja uchwytów w trakcie close, bounded SSLSocket close, cleanup gate po fatalnym validatorze, kontynuacja dispatch po fatalnym Error | `kotlinc -Werror` PASS; registry 25/25 + publisher 3/3 + binding 2/2 + owner/TLS/gate 24/24 = 54/54 lokalny JVM harness; Gradle/JUnit/APK/Android NOT RUN |
| 0.6.67-SOURCE | `AI_MASTER_PRO_0_6_67_COMPANION_REVOCATION_FAILURE_PATHS_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.66-SOURCE; dispatch revocation także po błędzie/niepewnym wyniku zapisu, wipe fail-closed, fatalny validator czyści aktywnego ownera | `kotlinc -Werror` core/store z atrapami Android/JSON PASS; registry/publisher/binding 30/30 + owner/TLS/gate 25/25 = 55/55 lokalny JVM harness; Gradle/JUnit/APK/Android NOT RUN |
| 0.6.68-SOURCE | `AI_MASTER_PRO_0_6_68_COMPANION_TWO_PHASE_FENCE_PRIMITIVE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.67-SOURCE; registry otrzymał beginRevocation/RevocationBatch: epoch fence i detach oddzielone od close socketów; store integration nadal OPEN | `kotlinc -Werror` registry PASS; 26/26 lokalny JVM harness; Gradle/JUnit/APK/Android NOT RUN |
| 0.6.69-SOURCE | `AI_MASTER_PRO_0_6_69_COMPANION_STORE_FENCE_INTEGRATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.68-SOURCE; pre-commit fence pod monitorem store, deferred cleanup poza monitorem, lifecycle-safe binding | `kotlinc -Werror` store/core z atrapami Android/JSON PASS; registry/publisher/binding 36/36 + owner/TLS/gate 25/25 = 61/61 metod lokalnego JVM harnessu + 1/1 scenariusz store integration harness; Gradle/JUnit/APK/Android NOT RUN |
| 0.6.70-SOURCE | `AI_MASTER_PRO_0_6_70_COMPANION_RUNTIME_LIFECYCLE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.69-SOURCE; szkic CompanionRuntime + CompanionRuntimeSlot, bez podłączenia do Application | `kotlinc -Werror` PASS; 64/64 lokalne metody JVM harnessu; Gradle/JUnit/APK/Android NOT RUN |
| 0.6.71-SOURCE | `AI_MASTER_PRO_0_6_71_COMPANION_CONCURRENT_CLOSE_HARDENING_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.70-SOURCE; utwardzenie współbieżnego close bindingu/runtime, testy oczekiwania wszystkich zamykających | `kotlinc -Werror` PASS; 37/37 binding/registry/publisher + 4/4 runtime = 41/41 lokalne metody lifecycle harnessu; Gradle/JUnit/APK/Android NOT RUN |
| 0.6.72-SOURCE | `AI_MASTER_PRO_0_6_72_COMPANION_APPLICATION_GRAPH_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.71-SOURCE; lazy application-process graph store/runtime, seam DI dla CompanionTrustService, bez podłączenia klasy Application | `kotlinc -Werror` PASS; graph 3/3 lokalny JVM harness; Gradle/JUnit/APK/Android NOT RUN |
| 0.6.73-SOURCE | `AI_MASTER_PRO_0_6_73_COMPANION_APPLICATION_OWNER_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.72-SOURCE; produkcyjna klasa CompanionApplication, application-scoped store, manifest i dwa call sites MainActivity przepięte; runtime/binding nadal draft | Statyczna kontrola manifestu/call sites PASS; Gradle test NOT RUN (UnknownHostException services.gradle.org); APK/device NOT RUN |
| 0.6.74-SOURCE | `AI_MASTER_PRO_0_6_74_COMPANION_RUNTIME_PRODUCTION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.73-SOURCE; promocja registry/binding/runtime do production source set; Application owns singleton runtime bound to shared store; JUnit-discoverable tests and Robolectric integration tests added | `kotlinc -Werror` PASS; local JVM shim runner 35/35 PASS; Gradle/Robolectric/assembleDebug/APK/device NOT RUN |
| 0.6.75-SOURCE | `AI_MASTER_PRO_0_6_75_COMPANION_TLS_GATE_PRODUCTION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.74-SOURCE; produkcyjny `CompanionTlsHandshakeGate`, gate przypięty do Runtime/Application; registry otrzymał permanent shutdown i unieważnianie ticketów przy zamykaniu runtime | `kotlinc -Werror` PASS; nowy gate/runtime shim 11/11 PASS; istniejący registry/binding/runtime 35/35 PASS; Application seam i manifest PASS; Gradle/JUnit/Robolectric/APK/device NOT RUN |
| 0.6.76-SOURCE | `AI_MASTER_PRO_0_6_76_COMPANION_SESSION_PROVISION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.75-SOURCE; dodano `CompanionProvisionedSession` i factory store wiążący dokładny profil TLS z ownerem; gate przyjmuje capability i odrzuca profile/owner mismatch przed ticketem | `kotlinc -Werror` Companion core PASS; lokalny shim-JUnit gate 3/3 + runtime 6/6 = 9/9 PASS; Android store compile/Gradle/JUnit/Robolectric/APK/device NOT RUN |
| 0.6.77-SOURCE | `AI_MASTER_PRO_0_6_77_COMPANION_TLS_PROFILE_LIFECYCLE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.76-SOURCE; jawny Closeable lifecycle profilu TLS, śledzenie listenerów/socketów, callback do zamknięcia ownera i zwolnienia registry, zamykanie provisioningu po błędzie factory | `kotlinc -Werror` Companion core + store z atrapami Android/JSON PASS; lokalny shim-JUnit 16/16 PASS; loopback mTLS cleanup PASS; Gradle/Robolectric/APK/device NOT RUN |

| 0.6.78-SOURCE | `AI_MASTER_PRO_0_6_78_COMPANION_PIN_ROTATION_REVOCATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.77-SOURCE; promocja staged SPKI wykonuje peer-scoped pre-commit fence, zamyka stare sesje po zapisie; stage zachowuje overlap | `kotlinc -Werror` Companion core PASS; pin rotation shim-JUnit 3/3 PASS; Android store/Gradle/Robolectric/Keystore/APK/device NOT RUN |
| 0.6.79-SOURCE | `AI_MASTER_PRO_0_6_79_COMPANION_ROTATION_CONCURRENCY_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.78-SOURCE; audyt wyścigów registry, process-local trust latch po nieudanej destrukcyjnej mutacji, best-effort usunięcie klucza metadata AES, precondition validation przed promote fence | `kotlinc -Werror` registry/pin-set/testy + store seam z atrapami Android/JSON PASS; shim-JUnit 33/33 × 5 uruchomień = 165/165; real Android SDK/Gradle/Robolectric/Keystore/APK/device NOT RUN |
| 0.6.80-SOURCE | `AI_MASTER_PRO_0_6_80_COMPANION_DURABLE_TRUST_LOCK_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.79-SOURCE; redundantny trwały fail-closed: process latch + marker SharedPreferences + atomowy marker noBackupFilesDir + Keystore key deletion fallback; wipe usuwa payload i markery przed unlock | `kotlinc -Werror` Companion core + rzeczywisty store z atrapami Android/JSON PASS; lokalny fault harness 4/4 + regresja shim-JUnit 6/6 PASS; Gradle/JUnit/Robolectric/Android Keystore/APK/device NOT RUN |

| 0.6.81-SOURCE | `AI_MASTER_PRO_0_6_81_COMPANION_LOCK_MARKER_CONCURRENCY_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.80-SOURCE; unikalne pliki tymczasowe markera trust, bezpieczny cleanup z zachowaniem suppressed, test współbieżności dwóch instancji/32 writerów dodany | Statyczna kontrola patcha PASS; test Robolectric ADDED / NOT RUN; Gradle/JUnit/Robolectric/Keystore/APK/device NOT RUN |
| 0.6.82-SOURCE | `AI_MASTER_PRO_0_6_82_COMPANION_DIRECTORY_FSYNC_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.81-SOURCE; fsync katalogu po atomowym rename markera oraz po jego usunięciu przed unlock; test Robolectric rozszerzony | Host JDK/Linux NIO 100/100 cykli PASS; statyczny patch PASS; Robolectric/Gradle/Android device NOT RUN |
| 0.6.83-SOURCE | `AI_MASTER_PRO_0_6_83_COMPANION_FAULT_INJECTION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.82-SOURCE; fault injection na granicach commit/move/fsync/delete/Keystore i wipe | Hostowy NIO fault matrix 200/200 PASS; testy Robolectric ADDED / NOT RUN |
| 0.6.84-SOURCE | `AI_MASTER_PRO_0_6_84_COMPANION_IO_ADAPTERS_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.83-SOURCE; wstrzykiwalny CompanionTrustIo, testy false commit/Keystore failure oraz wspólny monitor/latch dla instancji w jednym procesie | Kontrola statyczna PASS; testy Robolectric ADDED / NOT RUN; Gradle/Keystore/APK/device NOT RUN |
| 0.6.85-SOURCE | `AI_MASTER_PRO_0_6_85_COMPANION_CONCURRENCY_LINEARIZATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.84-SOURCE; publisher revocation współdzielony z process monitor/latch; audyt linearizacji promote/revoke/wipe; deterministyczne race tests registry | `kotlinc -Werror` PASS; host JVM race tests 15/15 PASS; test integracyjny Robolectric ADDED / NOT RUN; Gradle/Keystore/APK/device NOT RUN |
| 0.6.86-SOURCE | `AI_MASTER_PRO_0_6_86_MASTER_ENGINEERING_PROTOCOL_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.85-SOURCE; usunięcie fałszywej deklaracji pełnego dostępu do repo; protokół inżynierski agentów i koordynacji MASTER; playbook i testy promptów | `kotlinc -Werror` + lokalny shim runner 4/4 PASS; Gradle/JUnit/Robolectric/APK/Keystore/device NOT RUN |
| 0.6.87-SOURCE | `AI_MASTER_PRO_0_6_87_TYPED_VERIFICATION_EVIDENCE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.86-SOURCE; typowane dowody weryfikacji, assessment wymaganych kontroli, integracja ProjectTestRunner bez fałszywego PASS | `kotlinc -Werror`; lokalne shim runnery 9/9 PASS; Gradle/JUnit/Robolectric/APK/Keystore/device NOT RUN |
| 0.6.88-SOURCE | `AI_MASTER_PRO_0_6_88_PERSISTENT_VERIFICATION_EVIDENCE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.87-SOURCE; bounded persistent evidence ledger, Android SharedPreferences adapter, zapis dowodów w ręcznej weryfikacji patcha | `kotlinc -Werror`; 21/21 testów shim; Android backend stub compile PASS; Gradle/Android build NOT RUN |
| 0.6.89-SOURCE | `AI_MASTER_PRO_0_6_89_MASTER_EVIDENCE_SELF_CHECK_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.88-SOURCE; MASTER self-check rozróżnia dowody testów/kompilacji/Android build i odrzuca niepoparte claimy | `kotlinc -Werror`; lokalne shim runnery 29/29 PASS; Gradle/Android build NOT RUN |
| 0.6.90-SOURCE | `AI_MASTER_PRO_0_6_90_EVIDENCE_SCOPE_BINDING_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.89-SOURCE; dowody wiążą task/chat/snapshot, exact-scope lookup, ledger V2 z odczytem legacy V1 | `kotlinc -Werror` dla Evidence/Store/Runner; lokalne shim runnery 20/20 PASS; Android/Gradle build NOT RUN |
| 0.6.91-SOURCE | `AI_MASTER_PRO_0_6_91_HOST_TOOL_EXECUTION_REGISTRY_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.90-SOURCE; host-owned execution registry, host-generated executionId, durable NOT_RUN before start, one-shot PASS/FAIL completion | `kotlinc -Werror`; lokalne shim runnery 28/28 PASS; adaptery/Gradle/Android build NOT RUN |
| 0.6.92-SOURCE | `AI_MASTER_PRO_0_6_92_HOST_TOOL_ADAPTER_INTEGRATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.91-SOURCE; dodano EvidenceTrackedToolAdapter i podłączono ręczną weryfikację patcha do hostowego registry; historyczne rekordy bez chatId pozostają bez sztucznego scope | `kotlinc -Werror` PASS; lokalny shim-JUnit 38/38 PASS; MainActivity call site statycznie sprawdzony; Gradle/Android build/APK/device NOT RUN |
| 0.6.93-SOURCE | `AI_MASTER_PRO_0_6_93_VERIFICATION_EVIDENCE_RESOLVER_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.92-SOURCE; exact-scope VerificationEvidenceResolver, oddzielenie agent claims, walidacja par start/wynik TOOL_EXECUTION po executionId+kind+czasie; NOT_RUN start/abandon zawiera executionId | `kotlinc -Werror` PASS; lokalny shim-JUnit 48/48 PASS; Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN |
| 0.6.94-SOURCE | `AI_MASTER_PRO_0_6_94_MASTER_EVIDENCE_SYNTHESIS_INTEGRATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.93-SOURCE; ograniczony kontekst dowodów w MASTER_ONLY i syntezie zespołu; assessment w MasterResponseCheck | shim-JUnit 57/57 PASS wg statusu paczki; Gradle/Android build/APK NOT RUN |
| 0.6.95-SOURCE | `AI_MASTER_PRO_0_6_95_HOST_ACTIVE_EVIDENCE_SCOPE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.94-SOURCE; host-owned scope z ChangeSetStore, dokładnie jeden zastosowany ChangeSet w aktywnym czacie, bez prompt parsing i latest fallback | `kotlinc -Werror` + lokalny shim-JUnit 59/59 PASS; Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN |
| 0.6.96-SOURCE | `AI_MASTER_PRO_0_6_96_HOST_EXPLICIT_ACTIVE_CHANGESET_SCOPE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.95-SOURCE; trwały host `activeChangeSetId` powiązany z chatId, SHA-256 manifestu workspace, taskId i snapshotId; rollback/chat/workspace isolation, synchroniczny zapis ledgeru | `kotlinc -Werror` PASS; 65/65 lokalny harness PASS; nowe Robolectric testy ADDED/NOT RUN; Gradle/Android build/APK/device NOT RUN |
| 0.6.97-SOURCE | `AI_MASTER_PRO_0_6_97_CHANGESET_LEDGER_CORRUPTION_GUARD_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.96-SOURCE; jawny VALID/CORRUPT odczyt ChangeSet ledger, mutacje fail-closed bez nadpisywania korupcji, UI pokazuje błąd, host scope INVALID przy corrupt ledger | resolver harness 65/65 PASS; `kotlinc -Werror` compile-only shim dla store PASS; nowe Robolectric ADDED/NOT RUN; Gradle/Android build/APK/device NOT RUN |
| 0.6.98-SOURCE | `AI_MASTER_PRO_0_6_98_WORKSPACE_PROCESS_LOCK_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.97-SOURCE; wspólny reentrant process lock dla import/apply/rollback/odczytów workspace; UI non-blocking tryWithLock; testy wzajemnego wykluczania i reentrancy | resolver harness 68/68 PASS; ChangeSet store compile shim `kotlinc -Werror` PASS; Robolectric/Gradle/Android build/APK/device NOT RUN |


| 0.6.114-SOURCE | `AI_MASTER_PRO_0_6_114_CHANGESET_STORE_FAULT_MATRIX_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.113-SOURCE; hostowy fault harness wykonuje produkcyjne ChangeSetStore/HostActiveChangeSetStore z fake StringPreferenceStore i shimami Android/JSON; read/corrupt/write false+exception/active pointer retry/remove false+exception | store fault matrix 10/10, coordinator 13/13, commit gate 7/7, policy 11/11, protocol 8/8 = 49 hostowych przypadków PASS; compile-only harness timeout, ale nowe wykonawcze źródło kompiluje trzy klasy produkcyjne z -Werror; Robolectric/Gradle/Android/APK/device NOT RUN |

## Zasady rejestru
1. Zawsze zapisywać dokładną nazwę pliku ZIP i jego identyfikator.
2. Zapisywać `versionName` i `versionCode` osobno od numeru paczki źródłowej.
3. Zapisywać bazową paczkę/pochodzenie.
4. Oddzielać testy wykonane od statycznej analizy i testów NOT RUN.
5. Nowa paczka nie zastępuje poprzedniej; zachowujemy historię i status.
6. Przy wznowieniu w nowym oknie numer paczki identyfikuje wpis, ale ponowna analiza kodu wymaga dostępności odpowiedniego ZIP-a.

## Aktywna baza
- Najnowsza paczka źródłowa: 0.6.109-SOURCE.
- Źródła aplikacji: 0.6.54 / versionCode 80.
- Najnowsza paczka robocza: 0.6.109-SOURCE.
- Aktywna baza aplikacji: 0.6.54 / versionCode 80.
- R1: `CompanionTlsHandshakeGate` jest w produkcyjnym source set od 0.6.75; loopback mTLS potwierdza ticket-before-handshake, aktywację ownera i odrzucenie biletu po revocation podczas aktywacji. Gradle/JUnit/Robolectric nadal NOT RUN.
- R1/R2: produkcyjny registry/gate/owner handle od 0.6.75; revocation zamyka socket i ustawia owner FAILED. Runtime shutdown unieważnia bilety i zamyka aktywne handle.
- R3/R4: publisher, dispatch po commit i binding store→registry są w produkcyjnym source set od 0.6.74; testy pełnego Gradle/Robolectric nadal NOT RUN.
- R6: TTL i cleanup receipt-ów dodane w 0.6.61-SOURCE; testy limitu i pełnej integracji nadal wymagane.
- 0.6.62: test loopback potwierdza gate→owner→registry i natychmiastowe FAILED/socket close po registry revoke.
- 0.6.63: ticket admission jest jednorazowy; gate anuluje go także przy wyjątku handshake/activation. Rejestr nadal jest szkicem poza buildem.
- 0.6.64: naprawiono błąd, w którym stale/replayed ticket mógł odłączyć handle innego peera; dodano prunowanie epok i gwarantowany cleanup gate po błędzie ownera. Registry 23/23 lokalny shim; pełny build Android nadal NOT RUN.
- 0.6.65: lifecycle-safe binding store→registry; `close()` czeka na callback w toku.
- 0.6.66: rejestr rezerwuje uchwyty aż do końca zamykania poza blokadą; publisher i registry kontynuują wszystkie próby po fatalnym `Error`, a następnie propagują błąd; gate czyści owner/socket/receipt po błędzie aktywacji i stosuje bounded TLS socket close. 54/54 testów lokalnego harnessu; pełny build Android nadal NOT RUN.
- Następna czynność: pełny Gradle/JUnit/Robolectric i testy Android Keystore; następnie audyt rzeczywistego provisioningu profilu TLS oraz testy Android. Transport nadal nie jest automatycznie uruchamiany. Pkt 4 jest zintegrowany źródłowo, ale nie może być uznany za zweryfikowany buildem Android.

- 0.6.67: `revokePeer()` i `wipeAllTrust()` nie pomijają dispatchu sesji po błędzie lub niepewnym wyniku zmiany magazynu; fatalny alias cleanup nie omija globalnego revocation; owner czyści aktywną sesję po fatalnym błędzie trust validatora. 55/55 testów lokalnego harnessu; store/core `kotlinc -Werror` z atrapami Android/JSON PASS. Atomowa integracja store→registry nadal OPEN.

- 0.6.68: dodano dwufazowy prymityw registry `beginRevocation()`/`RevocationBatch`; faza 1 natychmiast unieważnia bilety i odłącza handle bez I/O, faza 2 zamyka zasoby po zwolnieniu zewnętrznego monitora. 26/26 testów lokalnego harnessu.
- 0.6.69: store rozpoczyna fence wewnątrz monitora przed commit; cleanup socketów wykonuje po wyjściu z monitora. Awaria przygotowania fence blokuje mutację, ale nie pomija cleanup częściowo przygotowanych uczestników. 61/61 metod testowych lokalnego harnessu + 1/1 scenariusz integracyjny `wipeAllTrust()` potwierdzający fence→commit→unlock→close; integracja lifecycle bindingu i Android build nadal wymagane.

- 0.6.70: dodano `CompanionRuntime` i `CompanionRuntimeSlot` jako szkic jednego właściciela registry/binding; powtarzane getOrCreate zwraca tę samą instancję, close odłącza binding i pozwala na świeży runtime. 64/64 lokalne metody harnessu JVM PASS. Holder nadal nie jest podłączony do Android `Application`, a `CompanionTrustService` tworzy osobny store; production lifecycle pozostaje OPEN.

- 0.6.71: binding i runtime używają OPEN/CLOSING/CLOSED; każdy równoległy wywołujący close czeka na cleanup, a błąd zamknięcia jest propagowany kolejnym wywołującym. 41/41 testów lokalnego harnessu lifecycle PASS. Wspólny graph Application/store nadal nie jest wdrożony.


## 0.6.73-SOURCE — Companion Application owner

`CompanionApplication` jest zarejestrowana w manifeście i posiada jeden leniwie tworzony `CompanionProvisioningStore` na proces. Oba call sites `MainActivity` pobierają service przez Application, więc współdzielą publisher revocation. Dodano dwa testy Robolectric. Gradle nie wystartował, ponieważ wrapper nie mógł pobrać Gradle 8.13 (`UnknownHostException services.gradle.org`); APK i testy urządzeniowe NOT RUN. Runtime/registry/binding zostały promowane w 0.6.74; na tym etapie nie były jeszcze uruchamiane przez aplikację.

- 0.6.74: registry, binding i runtime są w production source set; `CompanionApplication` współdzieli jeden store i runtime. 35/35 lokalnych metod JVM PASS z shimem JUnit; Gradle/Robolectric/APK/device nadal NOT RUN. `CompanionTlsHandshakeGate` został promowany w 0.6.75; transport nadal nie jest automatycznie uruchamiany.

## 0.6.76-SOURCE — spójny provisioning TLS owner/profile

- `CompanionProvisioningStore.createSessionProvision()` zwraca `CompanionProvisionedSession`, który łączy profil TLS z ownerem utworzonym na bazie dokładnie tej samej instancji.
- `CompanionTlsHandshakeGate` ma warianty accept/connect przyjmujące capability; wszystkie ścieżki admission sprawdzają referencyjne powiązanie ownera z profilem przed pobraniem ticketu.
- Starszy `createSessionOwner()` jest deprecated.
- `kotlinc -Werror` Companion core PASS; lokalny shim-JUnit runner gate 3/3 + runtime 6/6 = 9/9 PASS, w tym loopback mTLS i mismatch rejection. Android store compile, Gradle/Robolectric/APK/Keystore/device NOT RUN.
- Transport nie jest uruchamiany automatycznie; app version 0.6.54 / code 80 bez zmian.


## 0.6.77-SOURCE — pełny lifecycle profilu TLS
- `CompanionPinnedTlsProfile.close()` odcina nowe operacje i zamyka listenery, handshake in-flight, oczekujące receipts oraz aktywne sockety.
- Gate rejestruje callback zamknięcia profilu; aktywny owner jest terminalnie zamykany, a uchwyt zwalniany z registry. Wyścig close-vs-register jest fail-closed.
- `CompanionProvisionedSession.close()` zamyka ownera i dokładnie przypisany profil. Store sprząta profil, jeśli konstrukcja ownera zawiedzie.
- `kotlinc -Werror` Companion core PASS; store seam z atrapami Android/JSON PASS; lokalny shim-JUnit 16/16 PASS, loopback mTLS cleanup PASS. Gradle/JUnit/Robolectric/APK/Keystore/device NOT RUN.

| 0.6.94-SOURCE | `AI_MASTER_PRO_0_6_94_MASTER_EVIDENCE_SYNTHESIS_INTEGRATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.93-SOURCE; `MasterEvidenceContextBuilder` i exact-scope evidence context podłączony do MASTER_ONLY oraz team synthesis; assessment trafia do `MasterResponseCheck`; bez fallbacku do latest/whole ledger | `kotlinc -Werror` PASS; 57/57 lokalne shim-JUnit PASS; Gradle/Robolectric/Android build/APK/device NOT RUN |
| 0.6.99-SOURCE | `AI_MASTER_PRO_0_6_99_ROLLBACK_WORKSPACE_IDENTITY_GUARD_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.98-SOURCE; weryfikacja + identity w jednej sekcji lock, rollback tylko przy niezmienionym SHA manifestu, import + active-scope invalidation atomowo pod lockiem, jawny wynik partial invalidation | resolver/lock harness 73/73 PASS (`kotlinc -Werror`); ChangeSet/HostActive compile shim PASS; Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN |
| 0.6.100-SOURCE | `AI_MASTER_PRO_0_6_100_PATCH_TRANSACTION_COMPENSATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.99-SOURCE; preview wiąże workspace SHA + chatId, apply ponownie waliduje oba pod lockiem, kompensacyjny rollback i ledger reconciliation, post-swap recovery w ProjectWorkspaceStore, snapshot chroniony przed pruningiem | resolver/lock harness 73/73 PASS; ChangeSet/HostActive compile shim PASS; workspace core `kotlinc -Werror` PASS; JVM smoke import-preview-apply-restore-stale identity PASS; Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN |
| 0.6.101-SOURCE | `AI_MASTER_PRO_0_6_101_DURABLE_WORKSPACE_SWAP_JOURNAL_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.100-SOURCE; wersjonowany `workspace.swap.journal`, fsync + atomic move przed swapem, commit tylko po hash manifestu i pełnej weryfikacji drzewa, recovery commit/rollback fail-closed, kompatybilność legacy recovery | workspace transaction recovery harness 4/4 PASS; workspace core `kotlinc -Werror` PASS; Gradle/JUnit/Robolectric/Android build/APK/device NOT RUN |
| 0.6.102-SOURCE | `AI_MASTER_PRO_0_6_102_PATCH_LEDGER_RECOVERY_RECEIPT_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.101-SOURCE; journal v2 z ChangeSet/chat/snapshot/base identity, receipt v3 z rollbackRequested/rolledBack, startup ledger reconciliation, aktywacja pointera tylko dla dokładnego czatu/workspace, journal v1 kompatybilny | workspace transaction recovery harness 12/12 PASS; workspace core `kotlinc -Werror` PASS; resolver/lock 73/73 PASS; ChangeSet/HostActive compile shim PASS; Android/Gradle/Robolectric/APK/device NOT RUN |
| 0.6.103-SOURCE | `AI_MASTER_PRO_0_6_103_RECOVERY_POLICY_HARDENING_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.102-SOURCE; czysta polityka startup recovery wydzielona z MainActivity, jawne decyzje rollback/snapshot/status/active scope, harness idempotencji i awarii granicznych | recovery policy 11/11 PASS; workspace transaction recovery 12/12 PASS; resolver/lock 73/73 PASS; ChangeSet/HostActive compile shim PASS; MainActivity static integration 8/8 PASS; Gradle/Robolectric/Android/APK/device NOT RUN |
| 0.6.104-SOURCE | `AI_MASTER_PRO_0_6_104_SHARED_PREFS_FAULT_SEAMS_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.103-SOURCE; wprowadzono wstrzykiwalny StringPreferenceStore dla ChangeSetStore i HostActiveChangeSetStore; obsługa read exception, commit=false/exception i remove failure bez fałszywego potwierdzania zapisu; dodano 8 testów Robolectric | ChangeSet/HostActive compile shim `kotlinc -Werror` PASS; workspace recovery 12/12, policy 11/11, resolver 73/73 PASS; Robolectric/Gradle/Android/APK/device NOT RUN |

| 0.6.105-SOURCE | `AI_MASTER_PRO_0_6_105_HOST_LEDGER_STORAGE_SCOPE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.104-SOURCE; jawny `ledgerStorage` w HostActiveChangeSetStore, walidacja korzysta z tego samego wstrzykniętego źródła co test; dwa dodatkowe testy Robolectric | ChangeSet/HostActive compile shim `kotlinc -Werror` PASS; workspace recovery 12/12, policy 11/11, resolver 73/73 PASS; 32 metody @Test w ChangeSetStoreTest (2 nowe ADDED; cały zestaw NOT RUN), Gradle/Android/APK/device NOT RUN |
| 0.6.106-SOURCE | `AI_MASTER_PRO_0_6_106_WORKSPACE_LOCK_CONCURRENCY_HARDENING_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.105-SOURCE; osobny JVM harness produkcyjnego process lock: reentrancy, try-lock, wyjątki, mutual exclusion 8 wątków/1200 wejść | workspace operation lock 5/5 PASS; recovery policy 11/11; workspace transaction recovery 12/12; resolver/lock 73/73; ChangeSet/HostActive compile shim PASS; Gradle/Robolectric/Android/APK/device NOT RUN |
| 0.6.107-SOURCE | `AI_MASTER_PRO_0_6_107_PATCH_RECEIPT_PROTOCOL_FAULT_MATRIX_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.106-SOURCE; hostowy protocol fault harness oparty na produkcyjnej PatchReceiptRecoveryPolicy; crash/retry po snapshot/status/invalidation/pointer, rollback retry, import identity/chat switch i idempotent ack | protocol 8/8; recovery policy 11/11; workspace transaction 12/12; workspace lock 5/5; resolver 73/73; ChangeSet/HostActive compile shim PASS; Gradle/Robolectric/Android/APK/device NOT RUN |

| 0.6.108-SOURCE | `AI_MASTER_PRO_0_6_108_PATCH_RECEIPT_COORDINATOR_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.107-SOURCE; rzeczywisty `PatchReceiptRecoveryCoordinator` wydzielony z MainActivity i używany przez startup recovery; backend port umożliwia testowanie tej samej sekwencji produkcyjnej | coordinator 9/9; policy 11/11; protocol 8/8; workspace transaction 12/12; workspace lock 5/5; resolver 73/73; ChangeSet/HostActive compile shim PASS; Gradle/Robolectric/Android/APK/device NOT RUN |
| 0.6.109-SOURCE | `AI_MASTER_PRO_0_6_109_FILESYSTEM_RECOVERY_INTEGRATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.108-SOURCE; integracja produkcyjnego koordynatora z rzeczywistym `ProjectWorkspaceStore` na temp filesystem: import ZIP, patch apply, snapshot, receipt, retry, chat mismatch i rollback | filesystem integration 5/5; coordinator 9/9; protocol 8/8; policy 11/11; workspace transaction 12/12; lock 5/5; resolver 73/73; Gradle/Robolectric/Android/APK/device NOT RUN |
| 0.6.110-SOURCE | `AI_MASTER_PRO_0_6_110_RECOVERY_LOCK_ORDER_HARDENING_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.109-SOURCE; `currentChatId` volatile, lock-free `isCurrentChat()` usuwa lock inversion activity-monitor/workspace-lock; testy chat switch, dwóch recovery callers i import po reconcile | concurrency 3/3; filesystem integration 7/7; coordinator 9/9; protocol 8/8; policy 11/11; workspace lock 5/5; resolver 73/73; ChangeSet compile shim PASS; workspace transaction rerun TIMEOUT; Gradle/Robolectric/Android/APK/device NOT RUN |
| 0.6.111-SOURCE | `AI_MASTER_PRO_0_6_111_RECOVERY_ACTIVATE_ACK_ATOMICITY_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.110-SOURCE; `PatchReceiptRecoveryCommitGate` serializuje exact chat/workspace check, active pointer, chat recheck/invalidation i receipt acknowledge w jednej sekcji workspace lock; zamyka okno importu pomiędzy activate i ack | commit gate 5/5; concurrency 3/3; coordinator 9/9; protocol 8/8; policy 11/11; workspace lock 5/5; MainActivity static guard PASS; filesystem integration i resolver rerun TIMEOUT; Gradle/Robolectric/Android/APK/device NOT RUN |
| 0.6.112-SOURCE | `AI_MASTER_PRO_0_6_112_RECOVERY_COMMIT_FAILURE_RETRY_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.111-SOURCE; osobny `INVALIDATION_FAILED`, fail-closed przy nieudanej trwałej invalidation po chat switch; 3 nowe testy Robolectric dla active pointer/ack/invalidation failure i retry | commit gate 7/7; concurrency 3/3; coordinator 9/9; protocol 8/8; policy 11/11; MainActivity static guard PASS; 3 Robolectric ADDED/NOT RUN; filesystem/lock/resolver rerun TIMEOUT; Gradle/Android/APK/device NOT RUN |

| 0.6.113-SOURCE | `AI_MASTER_PRO_0_6_113_RECOVERY_COORDINATOR_RETRY_MATRIX_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.112-SOURCE; rozszerzenie hostowego fault harnessu produkcyjnego PatchReceiptRecoveryCoordinator o corrupt/read failure i retry po częściowych zapisach; bez zmian produkcyjnego kodu | Coordinator 13/13, commit gate 7/7, policy 11/11, protocol 8/8 = 39 hostowych przypadków PASS; statyczne guardy 7/7 PASS; concurrency timeout; Robolectric/Gradle/Android/APK/device NOT RUN |

| 0.6.115-SOURCE | `AI_MASTER_PRO_0_6_115_RECOVERY_ROLLBACK_RECEIPT_IDENTITY_GUARD_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.114-SOURCE; coordinator waliduje niezmienną tożsamość receipt i wymaga potwierdzonego zakończenia rollbacku przed dalszym recovery; 2 negatywne testy | coordinator 15/15; store fault 10/10; policy 11/11; protocol 8/8; concurrency 3/3; lock-order guard PASS = 47 hostowych przypadków PASS; commit gate TIMEOUT; Robolectric/Gradle/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.115
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_114_CHANGESET_STORE_FAULT_MATRIX_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Companion transport: BLOCKED.

| 0.6.116-SOURCE | `AI_MASTER_PRO_0_6_116_RECOVERY_RECEIPT_INPUT_VALIDATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.115-SOURCE; walidacja kompletności receipt i sprzecznych flag rollback przed backend I/O; dwa testy negatywne; dodany preflight Gradle/Android | coordinator 17/17; store fault 10/10; policy 11/11; protocol 8/8; concurrency 3/3 + lock-order guard; commit gate 7/7; workspace lock 5/5 = 61 hostowych przypadków PASS; compile shim PASS; filesystem/resolver TIMEOUT; Gradle/Robolectric/Android/APK/device NOT RUN |
| 0.6.117-SOURCE | `AI_MASTER_PRO_0_6_117_WORKSPACE_IDENTITY_RECOVERY_FENCE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.116-SOURCE; recovery dopuszcza wyłącznie bazową/docelową workspace identity z receipt; obca identity blokuje ledger I/O i mutacje | coordinator 18/18, policy 11/11, protocol 8/8, concurrency 3/3 + lock-order guard, workspace lock 5/5, ChangeSet fault 10/10 = 55 hostowych PASS; commit gate TIMEOUT; Gradle/Robolectric/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.117
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_117_WORKSPACE_IDENTITY_RECOVERY_FENCE_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Środowisko Android build: BLOCKED — brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny.
- Companion transport: BLOCKED.

| 0.6.118-SOURCE | `AI_MASTER_PRO_0_6_118_WORKSPACE_IDENTITY_RECOVERY_PRE_FENCE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.117-SOURCE; workspace identity fence przeniesiony przed `recoverRequestedRollback()` i powtórzony po rollbacku; obcy workspace nie uruchomi rollbacku ani snapshot/ledger I/O | coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, workspace lock 5/5, store fault 10/10, commit gate 7/7, filesystem integration 7/7, resolver 73/73 = 136 hostowych PASS; MainActivity lock-order guard PASS; Gradle/Robolectric/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.118
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_118_WORKSPACE_IDENTITY_RECOVERY_PRE_FENCE_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Środowisko Android build: BLOCKED — brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny w poprzednim preflight.
- Companion transport: BLOCKED.

| 0.6.119-SOURCE | `AI_MASTER_PRO_0_6_119_RECOVERY_RESTORE_RACE_HARDENING_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.118-SOURCE; commit gate wymaga invalidacji stale active scope przed ACK; testy restore/recovery i hostowego odtworzenia magazynu po przerwaniu rollbacku | commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, ChangeSet fault 10/10, workspace lock 5/5, resolver 73/73 = 147 hostowych PASS; MainActivity lock-order static guard PASS; Gradle/Robolectric/Android/APK/device NOT RUN |

| 0.6.120-SOURCE | `AI_MASTER_PRO_0_6_120_ACTIVE_POINTER_LEDGER_DIVERGENCE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.119-SOURCE; definitywna niespójność active pointer z poprawnym ledgerem powoduje trwałą invalidację; transient ledger read failure zachowuje wskaźnik | ChangeSet fault 13/13, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 5/5, resolver 73/73, workspace transaction recovery 12/12 = 162 hostowych PASS; MainActivity lock-order guard PASS; Robolectric/Gradle/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.120
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_120_ACTIVE_POINTER_LEDGER_DIVERGENCE_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Środowisko Android build: nadal niepotwierdzone; Gradle/Robolectric/Android compile NOT RUN.
- Companion transport: BLOCKED.


| 0.6.121-SOURCE | `AI_MASTER_PRO_0_6_121_AMBIGUOUS_SHARED_PREFS_COMMIT_RECOVERY_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.120-SOURCE; AndroidStringPreferenceStore kompensuje ambiguous commit=false/exception dla write/remove, zachowując wynik FAILURE; testy fake SharedPreferences symulują zmianę pamięci przed false | ChangeSet fault 15/15, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, workspace lock 5/5, workspace transaction recovery 12/12, resolver 73/73 = 155 hostowych PASS; lock-order guard PASS; filesystem integration TIMEOUT; Gradle/Robolectric/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.121
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_121_AMBIGUOUS_SHARED_PREFS_COMMIT_RECOVERY_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Android SharedPreferences/Robolectric/Gradle/Android build: NOT RUN.
- Kompensacja po nieudanym commit jest best-effort; brak gwarancji trwałości, jeśli również kompensacyjny commit zawiedzie.
- Companion transport: BLOCKED.

| 0.6.122-SOURCE | `AI_MASTER_PRO_0_6_122_AMBIGUOUS_POINTER_QUARANTINE_STARTUP_GATE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.121-SOURCE; HostActiveChangeSetStore quarantine po niepotwierdzonym write/remove obejmuje próbny i aktualnie widoczny pointer; MainActivity blokuje evidence przy pending workspace transaction/receipt | ChangeSet fault 17/17, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 5/5, workspace transaction recovery 12/12, resolver 73/73 = 166 hostowych PASS; MainActivity static guards PASS; Robolectric/Gradle/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.122
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_122_AMBIGUOUS_POINTER_QUARANTINE_STARTUP_GATE_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Niepotwierdzony active pointer jest quarantined; pending workspace transaction/receipt blokuje wybór dowodów aż do startup reconciliation.
- Android SharedPreferences/Robolectric/Gradle/Android build/process-death: NOT RUN; hostowe shimy nie dowodzą trwałości na dysku.
- Companion transport: BLOCKED.


| 0.6.123-SOURCE | `AI_MASTER_PRO_0_6_123_ATOMIC_STARTUP_EVIDENCE_GATE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.122-SOURCE; pending transaction probe + workspace identity + active pointer read są atomowe pod jednym `ProjectWorkspaceOperationLock.tryWithLock`; contention fail-closed; lazy gate PENDING/UNKNOWN nie czyta pointera | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 5/5, workspace transaction recovery 12/12, resolver 73/73 = 170 hostowych PASS; MainActivity atomic-lock static guard PASS; Robolectric/Gradle/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.123
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_123_ATOMIC_STARTUP_EVIDENCE_GATE_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Sprawdzenie pending receipt i odczyt active pointer są atomowe względem workspace mutation/recovery; lock contention blokuje wybór dowodów.
- Android preflight BLOCKED — brak wrapper JAR, Gradle i Android SDK; Gradle/Robolectric/Android SharedPreferences/process-death NOT RUN.
- Companion transport: BLOCKED.

| 0.6.124-SOURCE | `AI_MASTER_PRO_0_6_124_WORKSPACE_FAILURE_RECONCILIATION_LOCK_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.123-SOURCE; failure-path pending probe + opcjonalny REJECTED transition + active-pointer invalidation atomowe pod ProjectWorkspaceOperationLock; post-recovery identity + pointer diagnostic read atomowe; rozszerzone concurrency/static guards | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 7/7, workspace transaction recovery 12/12, resolver 73/73 = 172 hostowych PASS; MainActivity static guards PASS; Robolectric/Gradle/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.124
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_124_WORKSPACE_FAILURE_RECONCILIATION_LOCK_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Failure-path nie sprawdza już pending receipt poza wspólną blokadą: decyzja o REJECTED i invalidacja pointera są jedną sekcją krytyczną.
- Diagnostyczny odczyt identity + active pointer po recovery również jest atomowy.
- 172 hostowe przypadki PASS; Android preflight BLOCKED — brak wrapper JAR, Gradle i SDK oraz DNS do services.gradle.org; Android/Robolectric/APK/process-death NOT RUN.
- Companion transport: BLOCKED.

| 0.6.125-SOURCE | `AI_MASTER_PRO_0_6_125_PROJECT_TEST_RUNNER_LOCK_BOUNDARY_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.124-SOURCE; publiczny `ProjectTestRunner.verifyAppliedChange()` sam zdobywa wspólny workspace lock i deleguje do prywatnej sekcji locked; dodano JUnit concurrency test (ADDED/NOT RUN), reentrant tryWithLock test i source guard | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 8/8, workspace transaction 12/12, resolver 73/73 = 173 hostowych PASS; ProjectTestRunner source guard + MainActivity static guards PASS; Gradle/Robolectric/Android/APK/device NOT RUN |

## Aktywna baza — aktualizacja 0.6.125
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_125_PROJECT_TEST_RUNNER_LOCK_BOUNDARY_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; brak APK.
- Publiczny runner weryfikacji workspace sam chroni pełną sekwencję odczytów wspólną blokadą; callerzy zewnętrzni nie muszą polegać na niejawnej konwencji.
- 173 hostowe przypadki PASS; jeden nowy JUnit concurrency test ADDED / NOT RUN. Android preflight BLOCKED — brak wrapper JAR, Gradle i SDK oraz niedostępny DNS do `services.gradle.org`.
- Companion transport: BLOCKED.


| 0.6.126-SOURCE | `AI_MASTER_PRO_0_6_126_WORKSPACE_CALLSITE_GUARD_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.125-SOURCE; audyt 39 produkcyjnych wywołań API workspace: 37 bezpośrednio pod withLock/tryWithLock, 2 callbacki pod PatchReceiptRecoveryCommitGate; dodano delimiter-aware source guard i integrację z harness run.sh; bez zmian zachowania produkcyjnego Kotlin | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 8/8, workspace transaction 12/12, resolver 73/73 = 173 hostowych PASS; runner/MainActivity/new 39-call-site source guards PASS; Android/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.126
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_126_WORKSPACE_CALLSITE_GUARD_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- 39 produkcyjnych wywołań workspace sklasyfikowano i objęto automatycznym source guardem; 37 ma bezpośrednią blokadę, 2 są callbackami wykonywanymi przez komponent posiadający blokadę.
- 173 hostowe przypadki PASS; nowe source guardy PASS.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/Robolectric/Android/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED.

| 0.6.127-SOURCE | `AI_MASTER_PRO_0_6_127_CHAT_SWITCH_RECOVERY_LOCK_SERIALIZATION_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.126-SOURCE; chat-switch active-scope invalidation (`openChat`/`newChat`/`newAssistantChat`) pobiera wspólną blokadę workspace, serializując ją ze startup receipt recovery; Activity monitor -> workspace gate, recovery chat predicate pozostaje monitor-free; rozszerzono lock harness i trwały source guard | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 9/9, workspace transaction 12/12, resolver 73/73 = 174 hostowych PASS; static guards PASS; Android/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.127
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_127_CHAT_SWITCH_RECOVERY_LOCK_SERIALIZATION_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- Chat-switch invalidation jest serializowana z patch/recovery commit przez wspólną blokadę; lock order: Activity monitor -> workspace gate. Recovery callback `isCurrentChat()` pozostaje monitor-free.
- 174 hostowe przypadki PASS; compile shim i source/static guards PASS.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/Robolectric/Android/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED.


| 0.6.128-SOURCE | `AI_MASTER_PRO_0_6_128_FAIR_WORKSPACE_LOCK_PROBE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.127-SOURCE; `tryWithLock()` zmieniony z untimed `tryLock()` na fair timed zero-wait `tryLock(0, NANOSECONDS)`, ponieważ untimed overload może bargować mimo `ReentrantLock(true)`; przerwanie zwraca null i przywraca interrupt flag; dodano test runtime i source guard | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, recovery concurrency 3/3, filesystem integration 9/9, workspace lock 10/10, workspace transaction 12/12, resolver 73/73 = 175 hostowych PASS; all source guards PASS; Android/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.128
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_128_FAIR_WORKSPACE_LOCK_PROBE_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- `tryWithLock()` respektuje fair queue przez timed zero-wait overload; contention/interruption fail-closed (`null`), interrupt flag zachowana.
- 175 hostowych przypadków PASS; compile shim i source guards PASS.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/Robolectric/Android/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED.

| 0.6.129-SOURCE | `AI_MASTER_PRO_0_6_129_STAGED_IMPORT_PROTOCOL_DESIGN_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.128-SOURCE; audyt obecnego importZip + MainActivity lock boundary; formalna specyfikacja prepare/commit, owner token, immutable staging, commit-time revalidation, snapshot aktualnego workspace, replay/abort/orphan cleanup/process-death; dodano 12-case contract model + source guard; produkcyjny importer bez zmian, nadal pod global lock | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 10/10, workspace transaction 12/12, resolver 73/73, staged import model 12/12 = 187 hostowych PASS; source guards PASS; Android/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.129
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_129_STAGED_IMPORT_PROTOCOL_DESIGN_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- Staged import ma formalny kontrakt i 12-case model, ale nie jest jeszcze podłączony do produkcyjnego `ProjectWorkspaceStore`; ekstrakcja ZIP nadal odbywa się pod global workspace lock.
- 187 hostowych przypadków PASS; compile-only shim i source guards PASS. Kontraktowy model nie jest testem integracji produkcyjnego importu.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/Robolectric/Android/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED.


| 0.6.130-SOURCE | `AI_MASTER_PRO_0_6_130_STAGED_IMPORT_DURABLE_RECOVERY_MODEL_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.129-SOURCE; staged import contract model rozszerzony z 12 do 20 przypadków o trwały lifecycle/journal recovery: PREPARED orphan, COMMITTING bez swapu/OLD_CURRENT/NEW_CURRENT, SWAPPED_AWAITING_EVIDENCE, idempotentna invalidacja i terminalność; produkcyjny importer bez zmian | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 10/10, workspace transaction 12/12, resolver 73/73, staged model 20/20 = 195 hostowych PASS; source guards PASS; Android/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.130
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_130_STAGED_IMPORT_DURABLE_RECOVERY_MODEL_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- Dodano model stanów i restart recovery staged importu; 20/20 testów modelu PASS. To nadal model in-memory, nie rzeczywisty journal ani produkcyjna integracja.
- 195 hostowych przypadków PASS; compile-only shim i source guards PASS. Produkcyjny ZIP import nadal odbywa się pod global workspace lock.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/Robolectric/Android/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED.


| 0.6.131-SOURCE | `AI_MASTER_PRO_0_6_131_STAGED_IMPORT_JOURNAL_FILESYSTEM_PROTOTYPE_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.130-SOURCE; niezależny hostowy prototyp wersjonowanego journalu plikowego: SHA-256 payloadu, bounded/fail-closed parser, temp + force + ATOMIC_MOVE, fault injection przed rename; produkcyjny importer bez zmian | ChangeSet 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, filesystem integration 9/9, workspace lock 10/10, workspace transaction 12/12, resolver 73/73, staged contract 20/20, journal filesystem 8/8 = 203 hostowe PASS; source guards PASS; Android/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.131
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_131_STAGED_IMPORT_JOURNAL_FILESYSTEM_PROTOTYPE_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- Nowy journal to wyłącznie hostowy prototyp filesystemowy 8/8; nie jest podłączony do produkcyjnego store. `ATOMIC_MOVE` i `force(true)` nie są dowodem Android power-loss durability.
- 203 hostowe przypadki PASS; compile-only shim i source guards PASS. Produkcyjny ZIP import nadal odbywa się pod global workspace lock.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Gradle/Robolectric/Android/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED.

| 0.6.132-SOURCE | `AI_MASTER_PRO_0_6_132_PRODUCTION_SWAP_JOURNAL_BOUNDS_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.131-SOURCE; audyt rzeczywistego AISW swap journal; produkcyjny reader wymaga zwykłego pliku, odrzuca symlink/path escape i ogranicza rozmiar do 1..16 KiB przed parsingiem; zachowano v1/v2 i EOF guard; dodano 3 testy JUnit ADDED/NOT RUN oraz source guard | Istniejąca regresja: ChangeSet 21, commit gate 9, coordinator 19, policy 11, protocol 8, concurrency 3, workspace lock 10, workspace transaction 12, resolver 73, filesystem integration 9, staged model 20, journal prototype 8 = 203 host PASS; compile shim + source guards PASS; nowe JUnit tests NOT RUN; Android/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.132
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_132_PRODUCTION_SWAP_JOURNAL_BOUNDS_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- Produkcyjny AISW journal ma jawny limit 16 KiB i kontrolę regular-file/symlink/canonical-parent przed parsingiem; wersje 1 i 2 pozostają obsługiwane.
- Trzy testy JUnit dodane (oversized/trailing bytes/legacy v1), ale NOT RUN. Istniejąca hostowa regresja: 203 PASS; compile shim/source guards PASS.
- Staged importer nie jest wdrożony; ZIP import nadal pod global lock. Hostowy journal 0.6.131 pozostaje niezależnym prototypem.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; Gradle/Robolectric/APK/device/process-death NOT RUN. Companion Transport BLOCKED.

| 0.6.133-SOURCE | `AI_MASTER_PRO_0_6_133_VERIFIED_ORPHAN_PREVIOUS_CLEANUP_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.132-SOURCE; po zweryfikowaniu current/ względem manifestu recovery czyści również osierocone `.previous-*` i `.previous-*.manifest`; symlinki usuwane jako wpisy; bez zmian AISW v1/v2/commit point; dodano host recovery scenario i JUnit test (ADDED/NOT RUN) | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, workspace lock 10/10, workspace transaction 13/13, resolver 73/73, filesystem integration 9/9, staged model 20/20, staged journal prototype 8/8 = 204 hostowych PASS; source guards PASS; Android/JUnit/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.133
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_133_VERIFIED_ORPHAN_PREVIOUS_CLEANUP_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- Recovery sprząta osierocone previous artifacts tylko po pełnej walidacji aktualnego workspace; gdy current jest niekompletny lub niezgodny, backupy pozostają nietknięte.
- 204 hostowe przypadki PASS; rzeczywisty `ProjectWorkspaceStore.kt` jest kompilowany i wykonywany w 13-case host harness. Nowy JUnit test ADDED / NOT RUN.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Android/Robolectric/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED. Produkcyjny ZIP import nadal pod global workspace lock; staged import nie jest zintegrowany.
| 0.6.134-SOURCE | `AI_MASTER_PRO_0_6_134_FAIL_CLOSED_TEMP_EVIDENCE_CLEANUP_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.133-SOURCE; naprawiono kolejność `cleanupInterruptedStaging()`: pełna walidacja current/manifest przed usunięciem staging/temp/receipt/previous; przy nieweryfikowalnym current zachowuje dowody transakcji; symlink temp usuwany bez dereferencji; workspace harness rozszerzony 13→15; JUnit ADDED/NOT RUN | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, workspace lock 10/10, workspace transaction 15/15, resolver 73/73, filesystem integration 9/9, staged model 20/20, staged journal prototype 8/8 = 206 host PASS; source guards PASS; Android/JUnit/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.134
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_134_FAIL_CLOSED_TEMP_EVIDENCE_CLEANUP_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- `cleanupInterruptedStaging()` najpierw weryfikuje całe current tree względem manifestu. Przy nieprawidłowym current nie usuwa żadnych staging/temp/receipt/previous artifacts. Wykryty bug: wcześniej temp cleanup następował przed integrity check.
- Rzeczywisty host harness `ProjectWorkspaceStore.kt`: 15/15; pełna regresja 206 host PASS; compile shim i source guards PASS. Nowy JUnit test ADDED / NOT RUN.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; Android/JUnit/Robolectric/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED. Produkcyjny ZIP import nadal pod global workspace lock; staged import nie jest zintegrowany.

| 0.6.135-SOURCE | `AI_MASTER_PRO_0_6_135_TEMP_WRITER_OWNERSHIP_GUARD_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.134-SOURCE; oba produkcyjne writery swap journal i patch receipt śledzą ownership temp path i czyszczą wyłącznie po udanym CREATE_NEW; helper unlinkuje wpis bez dereferencji symlinka; 4 nowe host runtime przypadki, 2 JUnit ADDED/NOT RUN i writer ownership source guard | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, workspace lock 10/10, workspace transaction 19/19, resolver 73/73, filesystem integration 9/9, staged model 20/20, staged journal prototype 8/8 = 210 host PASS; source guards PASS; Android/JUnit/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.135
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_135_TEMP_WRITER_OWNERSHIP_GUARD_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- `writeSwapJournal()` i `writePatchReceipt()` usuwają temp wyłącznie, gdy bieżący writer utworzył go przez `createNewFile()`. Obcy/pre-existing collision pozostaje nietknięty; cleanup usuwa wpis, nie dereferuje symlinka.
- 210 hostowych przypadków PASS; rzeczywisty `ProjectWorkspaceStore.kt` kompilowany i wykonywany w 19-case harness. Compile shim i source guards PASS. Dwa JUnit testy ADDED / NOT RUN.
- Android preflight BLOCKED: brak wrapper JAR, systemowego Gradle i Android SDK; DNS `services.gradle.org` niedostępny. Android/JUnit/Robolectric/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED. Produkcyjny ZIP import nadal pod global workspace lock; staged import nie jest zintegrowany.


| 0.6.136-SOURCE | `AI_MASTER_PRO_0_6_136_DURABLE_WRITER_FAULT_SEAM_SOURCE.zip` | 0.6.54 | 80 | Pochodna 0.6.135-SOURCE; oba produkcyjne writery używają wspólnego internal durable publishera z lokalnymi seamami `flushAndSync`/`moveAtomically`/`syncDirectory`; testy 9 fault points, bez non-atomic fallback; AISW/receipt/commit point bez zmian | ChangeSet fault 21/21, compile shim PASS, commit gate 9/9, coordinator 19/19, policy 11/11, protocol 8/8, concurrency 3/3, workspace lock 10/10, workspace transaction + publisher 28/28, resolver 73/73, filesystem integration 9/9, staged model 20/20, staged journal prototype 8/8 = 219 host PASS; source guards PASS; 4 JUnit ADDED/NOT RUN; Android/Gradle/Robolectric/APK/device/process-death NOT RUN (preflight BLOCKED) |

## Aktywna baza — aktualizacja 0.6.136
- Najnowsza paczka źródłowa: `AI_MASTER_PRO_0_6_136_DURABLE_WRITER_FAULT_SEAM_SOURCE.zip`.
- Źródła aplikacji: `0.6.54 / versionCode 80`; APK nie utworzono.
- `writeSwapJournal()` i `writePatchReceipt()` korzystają ze wspólnego `publishWorkspaceDurableFile()`, który kontroluje create/ownership, serializację, flush, `fd.sync()`, `ATOMIC_MOVE`, directory sync i cleanup. Seamy fault injection są internal i przekazywane per wywołanie.
- 219 host runtime PASS w 12 harnessach; rzeczywisty `ProjectWorkspaceStore.kt` przechodzi 28/28 workspace transaction/fault-injection cases; compile shim i source guards PASS. Cztery JUnit testy ADDED / NOT RUN.
- Brak nieatomowego fallbacku; directory fsync best-effort; AISW v1/v2, receipt format, commit point i lock scope bez zmian.
- Android preflight BLOCKED: brak wrapper JAR, Gradle i Android SDK; Android/JUnit/Robolectric/APK/device/process-death NOT RUN.
- Companion Transport: BLOCKED. Staged import nie jest wdrożony; ZIP import pozostaje pod global workspace lock.
