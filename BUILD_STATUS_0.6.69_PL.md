# BUILD STATUS — 0.6.69-SOURCE

**Typ:** integracja dwufazowego revocation fence z magazynem zaufania Companion; paczka źródłowa, bez APK.  
**Baza:** 0.6.68-SOURCE.  
**Wersja aplikacji:** `0.6.54` / `versionCode=80` (bez zmian).

## Zmiana end-to-end

1. Dodano `CompanionTrustRevocationFenceListener` — kontrakt pre-commit. `begin(event)` ma wykonywać wyłącznie nieblokujące unieważnienie admission i zwracać `Closeable` z odroczonym cleanupem.
2. `CompanionTrustRevocationPublisher.beginRevocation()` uruchamia wszystkich zarejestrowanych uczestników fence i zbiera cleanup-y oraz błędy; awaria jednego uczestnika nie pomija pozostałych.
3. `CompanionTrustRevocationBinding` rejestruje fence listener, który w fazie 1 wywołuje `registry.beginRevocation(peerId)`. Zamykanie socketów następuje dopiero przez `RevocationBatch.close()`.
4. `CompanionProvisioningStore.revokePeer()` oraz `wipeAllTrust()` rozpoczynają fence **wewnątrz monitora store, przed zmianą trwałego magazynu**. Bilety wydane przed fence stają się nieaktualne przed commit.
5. Po opuszczeniu monitora store wykonywany jest cleanup wszystkich odłączonych uchwytów, a następnie zwykłe powiadomienia post-mutation. Nie ma socket I/O w sekcji krytycznej store.
6. Jeśli przygotowanie fence nie powiedzie się, zmiana pinów jest wstrzymana, ale cleanup wszystkich częściowo przygotowanych fence nadal jest wykonywany poza monitorem. Jeśli zapis nie powiedzie się lub ma niepewny wynik, aktywne sesje pozostają unieważnione.
7. `Binding.close()` usuwa uczestnika i czeka na wszystkie rozpoczęte cleanup-y; callback ze snapshotu publishera po zamknięciu nie może rozpocząć nowego fence.
8. `RevocationBatch.close()` i `FenceBatch.close()` serializują równoległych wywołujących: drugi `close()` nie wraca, zanim pierwsze cleanup nie zakończy prób zamknięcia wszystkich zasobów.

## Walidacja lokalna

- `kotlinc -Werror` dla core + `CompanionProvisioningStore` z atrapami Android/JSON: PASS.
- Registry + publisher + binding: **36/36** metody lokalnego harnessu JVM PASS (27 registry + 6 publisher + 3 binding).
- Owner/TLS/gate: **25/25** metod lokalnego harnessu JVM PASS, w tym loopback mTLS.
- Dodatkowy harness integracyjny rzeczywistego `CompanionProvisioningStore.wipeAllTrust()` z atrapami Android: **1/1 PASS**. W callbacku `commit()` potwierdzono, że registry jest już odłączone, ale socket nie jest jeszcze zamknięty; sam `close()` ponownie wchodzi do `store.readPeerPins()`, co dowodzi, że nie działa pod monitorem store. Wymuszony błąd AndroidKeyStore po udanym commit nie pomija cleanup.
- Wyniki: 61/61 metod testowych lokalnego harnessu + 1/1 scenariusz integracyjny PASS.
- Harness używa shimów asercji JUnit; nie jest uruchomieniem JUnit ani Gradle.

## Ograniczenia

- Registry, gate i binding nadal pozostają w `drafts/pkt4`, poza produkcyjnym source set. Store ma już kontrakt i wywołania fence, ale aplikacja nie korzysta jeszcze z bindingu, dopóki nie zostanie on jawnie skonstruowany i podłączony w cyklu życia Companion.
- Nie wykonano `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, rzeczywistego JUnit/Robolectric, Android Keystore ani testów na urządzeniu.
- Nie utworzono APK; wersja aplikacji nadal `0.6.54` / code `80`.

## Następny krok

Zaprojektować i przetestować bezpieczny punkt instalacji `CompanionTrustRevocationBinding` w lifecycle Companion, a następnie testy integracyjne Store→Fence→Registry→Gate na Android/JUnit. Nie promować szkicu do produkcji przed tymi bramkami.
