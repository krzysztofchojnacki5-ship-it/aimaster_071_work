# Changelog — AI MASTER PRO 0.6.49

## Punkt 5.1e — zapis i odczyt łańcucha publicznego leafa TLS
- `CompanionTlsChainCodec`: format binarny z magią `AMCT`, wersją formatu, numerem wersji rekordu, aliasem, łańcuchem DER i trailerem SHA-256. Limity: do 3 certyfikatów, 8 KB na certyfikat, alias do 64 bajtów.
- `CompanionTlsChainRepository`: zapis i odczyt rekordu. Zasady:
  - uszkodzony magazyn nigdy nie jest po cichu nadpisywany (`STORE_CORRUPT`);
  - zapis wymaga ściśle wyższej wersji (`VERSION_NOT_MONOTONIC`);
  - odczyt zawsze ponownie waliduje łańcuch (`CompanionTlsLeafPolicy`) względem zaufanych rootów, zatwierdzonych nazw, klucza z Keystore dla aliasu i bieżącego czasu;
  - błąd zapisu jest zgłaszany (`WRITE_FAILED`), a nie połykany.
- `AndroidTlsChainBlobStore` (`data/`): SharedPreferences z `commit()`. **NOT RUN**.

## Granice bezpieczeństwa (jawnie)
- SHA-256 wykrywa uszkodzenia, ale NIE uwierzytelnia danych: kto może zmienić bajty, może przeliczyć digest. Uwierzytelnienie daje ponowna walidacja przy każdym odczycie.
- Ochrona przed wycofaniem całego magazynu (rollback) NIE jest zaimplementowana. Wymaga licznika zakotwiczonego sprzętowo; pozostaje otwarta.
- Łańcuch jest publiczny, więc nie jest szyfrowany. Klucz prywatny pozostaje wyłącznie w Keystore.

## Testy
- `CompanionTlsChainRepositoryTest` (13 testów): kodek (round-trip, uszkodzenie, obcięcie, niezgodność aliasu), odczyt poprawny, zły klucz, wygasły, brak klucza, wersje, uszkodzony magazyn, brak danych, błąd zapisu.

## Granice
- Kod Kotlin nie został skompilowany ani uruchomiony (NOT RUN).
- Transport CA i wykorzystanie zapisanego łańcucha w handshake — poza zakresem.
