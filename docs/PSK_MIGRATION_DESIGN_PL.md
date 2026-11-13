# Projekt 2 — migracja i przechowywanie PSK parowania Companion

Status: PROJEKT. Brak zmian w kodzie produkcyjnym. Wymaga decyzji z sekcji 7 przed implementacją.

## 1. Stan faktyczny (fakty z kodu, wersja 0.6.49)
- PSK (`pairingSecret`, 16–64 bajty) jest tylko parametrem: `CompanionPairingAuthority`, `CompanionSessionOwner`, `CompanionFrameProtocol.deriveSessionKey`, `CompanionProvisioningStore.createSessionOwner`.
- W kodzie produkcyjnym NIE MA producenta PSK ani zapisu PSK. Jedyny sekret w testach to stały fixture `ByteArray(32)`.
- `MainActivity.kt` nie odwołuje się do parowania ani do Companion.
- `CompanionProvisioningStore` trwale zapisuje wyłącznie piny SPKI (zaszyfrowane AES-GCM kluczem z Keystore), nie PSK.
- `AndroidManifest.xml`: `android:allowBackup="false"` — preferencje nie trafiają do kopii w chmurze.
- Eksport „zaszyfrowanej bazy” (`MainActivity`) zawiera wyłącznie `store.allConfigs()` (konfiguracje dostawców). Dane Companion nie są eksportowane. Szyfrowanie: PBKDF2-HMAC-SHA256, 210 000 iteracji.

Wniosek: w obecnym kodzie nie ma istniejącego PSK do zmigrowania. Migracja jest potrzebna tylko wtedy, gdy PSK istnieje poza tym repozytorium (patrz sekcja 7, pytanie 1).

## 2. Ograniczenie protokołu (ważne dla wyboru modelu)
`CompanionFrameProtocol.deriveSessionKey` używa `SecretKeySpec(pairingSecret, "HmacSHA256")`, czyli surowe bajty PSK muszą być dostępne w pamięci. Klucz Keystore nieeksportowalny nie może być użyty przez ten kod bez przepisania protokołu na `Mac` z kluczem Keystore.

## 3. Model docelowy (rekomendowany)
- PSK generowany na urządzeniu przez `SecureRandom` (32 bajty) w trakcie parowania, NIE importowany z zewnątrz.
- Przechowywanie: rekord `{ version, peerId, iv, ciphertext }` zaszyfrowany AES-GCM kluczem metadanych z Keystore (jak `CompanionProvisioningStore`), AAD = `AI_MASTER_COMPANION_PSK_V1|<peerId>|<version>`.
- Wersja monotoniczna: zapis wymaga wyższej wersji; cofnięcie = `REPAIR_REQUIRED`.
- Zapis atomowy przez `commit()`; wynik sprawdzany.
- Docelowo (etap 2b): klucz HMAC nieeksportowalny w Keystore zamiast surowych bajtów. Wymaga przepisania protokołu i testów na urządzeniu.

## 4. Stany przechowywania
- `NONE` — brak rekordu, parowanie wymagane.
- `ACTIVE(version)` — poprawny rekord, odszyfrowany i zweryfikowany.
- `MIGRATION_PENDING` — migracja w toku; nie wolno jej powtarzać automatycznie w niejednoznacznym stanie.
- `REPAIR_REQUIRED` — niejednoznaczny lub uszkodzony stan; wymagane jawne ponowne parowanie. Nigdy automatyczne odtworzenie zaufania.

## 5. Scenariusze migracji
- S0 — brak legacy (obecny stan): brak migracji; PSK powstaje przy pierwszym parowaniu.
- S1 — legacy w postaci jawnej: (1) odczyt legacy do pamięci; (2) zapis rekordu v+1 z AAD; (3) weryfikacja przez odszyfrowanie i porównanie; (4) atomowy commit; (5) usunięcie legacy z commit i sprawdzenie wyniku; (6) stan `ACTIVE`. Błąd na dowolnym kroku po (2) → `REPAIR_REQUIRED`, legacy NIE jest usuwane przed potwierdzeniem nowego rekordu.
- S2 — legacy zaszyfrowane innym kluczem (np. innym aliasem Keystore): brak automatycznej migracji; `REPAIR_REQUIRED`. Utrata lub niedostępność klucza jest zawsze jawnym recovery.
- S3 — jednocześnie legacy i nowy rekord: niejednoznaczne → `REPAIR_REQUIRED`; nie wybieramy po cichu.

## 6. Zasady bezpieczeństwa
- PSK nigdy nie trafia do logów, wyjątków, komunikatów UI ani eksportu.
- Tablice bajtów PSK są zerowane po użyciu (istniejąca praktyka w `CompanionSessionOwner`).
- `allowBackup=false` pozostaje bez zmian.
- Brak kopii PSK w kodzie aplikacji, w zasobach ani w bundle.

## 7. Decyzje wymagane przed implementacją
1. **Czy istniejący PSK istnieje gdziekolwiek poza repozytorium** (poprzednie wersje, urządzenie testowe, zapis zewnętrzny)? Jeśli nie — migracja nie jest potrzebna; wystarczy S0 i implementacja nowego przechowywania.
2. **Model klucza:** etap przejściowy z surowym PSK zaszyfrowanym at-rest (szybciej, zgodne z obecnym protokołem) czy od razu klucz HMAC w Keystore (bezpieczniej, większy zakres, testy tylko na urządzeniu)?
3. **Osobny problem znaleziony przy przeglądzie, poza Companion:** `SecureStore.key()` przy DOWOLNYM wyjątku wywołuje `store.deleteEntry(alias)` i generuje nowy klucz. Przy przejściowym błędzie Keystore to trwale niszczy klucz, którym zaszyfrowane są zapisane klucze API dostawców, bez ostrzeżenia. Jest to ten sam wzorzec, którego projekt zakazuje dla Companion ("utrata klucza nie może powodować automatycznego odtworzenia"). Propozycja: nie usuwać klucza przy wyjątku; usuwać tylko przy `KeyPermanentlyInvalidatedException` i po potwierdzeniu użytkownika. To zmienia widoczne zachowanie aplikacji, więc wymaga akceptacji.

## 8. Plan testów (po decyzjach; NOT RUN)
- Round-trip rekordu PSK z AAD; zły peerId lub wersja w AAD → odrzucenie.
- Cofnięcie wersji → `REPAIR_REQUIRED`.
- Uszkodzony ciphertext → `REPAIR_REQUIRED`, bez wycieku bajtów.
- S1: awaria między krokami (2) a (4) → legacy zachowane, stan `REPAIR_REQUIRED`.
- S3: legacy i nowy rekord równocześnie → `REPAIR_REQUIRED`.
- Brak PSK w logach (test na wyjątkach i komunikatach).
