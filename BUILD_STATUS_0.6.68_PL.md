# BUILD STATUS — 0.6.68-SOURCE

**Typ:** przygotowanie dwufazowego fence admission/revocation w szkicu Companion; paczka źródłowa, bez APK.  
**Baza:** 0.6.67-SOURCE.  
**Wersja aplikacji:** `0.6.54` / `versionCode=80` (bez zmian).

## Zmiana

1. `CompanionConnectionRegistry` otrzymał `beginRevocation(peerId)` zwracający `RevocationBatch`.
2. Faza 1 (wywołanie `beginRevocation`) pod blokadą registry zwiększa epokę i odłącza wszystkie właściwe uchwyty, ale nie wykonuje socket I/O.
3. Faza 2 (`RevocationBatch.close()`) zamyka odłączone uchwyty dopiero wtedy, gdy wywołujący jawnie zakończy sekcję krytyczną zewnętrznego magazynu.
4. `RevocationBatch.close()` jest idempotentne. `revoke(peerId)` zachowuje dotychczasowe zachowanie wygodnego wywołania: wykonuje obie fazy od razu.
5. Dodano regresję potwierdzającą, że stary ticket jest odrzucany natychmiast po fazie 1, podczas gdy zamknięcie istniejącego połączenia jest odroczone do fazy 2.

## Walidacja lokalna

- `kotlinc -Werror` dla registry i jego testów: PASS.
- Lokalny JVM harness: **26/26 PASS** (shim JUnit, nie jest to uruchomienie JUnit/Gradle).
- ZIP integrity: sprawdzona po utworzeniu paczki.

## Ograniczenia — ważne

- To jest tylko prymityw dwufazowy w szkicu `drafts/pkt4`; **nie jest jeszcze podłączony do `CompanionProvisioningStore`**.
- Luka commit→epoch na granicy store nadal nie jest zamknięta. Nie wolno uznawać atomowej revocation za gotową ani promować registry/gate/binding do produkcyjnego source set.
- Nie wykonano `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, JUnit, Robolectric, Android Keystore ani testów na urządzeniu.
- Nie utworzono APK; wersja aplikacji nadal `0.6.54` / code `80`.

## Następny krok

Dodać jawny kontrakt pre-commit fence do źródła magazynu zaufania: store musi rozpocząć fence wewnątrz własnego monitora przed zatwierdzeniem zmiany pinów, zebrać partie do zamknięcia, zwolnić monitor i dopiero wtedy wykonać ich `close()`. Testy muszą dowieść kolejności fence→commit→unlock→socket close oraz fail-closed przy błędzie commit.
