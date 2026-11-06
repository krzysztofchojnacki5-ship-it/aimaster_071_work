# Changelog — AI MASTER PRO 0.6.51

## Punkt 3 — jawne odzyskiwanie i ponowne parowanie
- `CompanionTrustRecovery` (czysta logika):
  - ocena stanu z migawki (piny, PSK, łańcuch, klucz TLS) → `Unpaired`, `PairedTlsPending`, `Paired`, `RepairRequired(powody)`;
  - nowe parowanie tylko z `Unpaired`; każdy niespójny stan (np. piny bez PSK, PSK bez pinów, łańcuch bez klucza) wymaga naprawy;
  - `recover` wymaga dokładnego potwierdzenia tekstem `PONOWNE PAROWANIE`; usuwa w stałej kolejności: piny → PSK → łańcuch → klucze TLS; przerwane odzyskiwanie zostawia stan niespójny, więc ponowienie jest bezpieczne; nic nie jest odtwarzane ze starych danych.
- Dodano `clear()` do `CompanionTlsBlobStore` (potrzebne do odzyskiwania).
- `CompanionProvisioningStore.wipeAllTrust()`: usuwa zaszyfrowany rekord pinów i klucz metadanych (stare zaufanie nie da się odszyfrować).
- `AndroidTlsKeyProvider`: `hasAnyTlsKey()`, `deleteAllTlsKeys()` (tylko aliasy `ai_master_companion_tls_v*`).
- `AndroidTrustRecoveryTargets`: adapter łączący pamięć z logiką (NOT RUN).
- Częściowo zamyka lukę z 0.6.45: usunięcie samych pinów przy zachowanym PSK jest teraz wykrywane jako `PSK_WITHOUT_PINS`, a nie czytane jako pusty magazyn.

## Naprawa importu konfiguracji (widoczny błąd)
- `replaceAllConfigs` kasował stare konfiguracje PRZED zapisem nowych i ignorował błędy; aplikacja mimo to pokazywała „Wczytano … wraz z kluczami API”.
- Teraz: najpierw zapis wszystkich konfiguracji; usuwanie starych tylko po pełnym sukcesie; zwraca `Boolean`.
- Oba wywołania w `MainActivity` pokazują błąd i nie raportują sukcesu, gdy zapis się nie powiódł.

## Testy
- `CompanionTrustRecoveryTest` (10 testów): ocena stanów, blokada nowego parowania, potwierdzenie, kolejność, przerwanie i ponowienie.
- Atrapy blobów w testach `CompanionTlsChainRepositoryTest` i `CompanionPskRepositoryTest` uzupełnione o `clear()`.

## Granice (ważne)
- Odzyskiwanie NIE jest podpięte do interfejsu: Companion nie jest w `MainActivity`, więc użytkownik nie ma jeszcze drogi do wywołania `recover`. To świadoma granica, nie błąd wykonania.
- Kod Kotlin nie został skompilowany ani uruchomiony (NOT RUN). Wszystkie testy JVM: NOT RUN.
- Operacje Keystore i prefs (`wipeAllTrust`, `deleteAllTlsKeys`, adapter) wymagają urządzenia: NOT RUN.
