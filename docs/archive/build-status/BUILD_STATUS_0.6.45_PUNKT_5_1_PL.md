# AI MASTER PRO — status: punkt 5.1 (projekt cyklu życia TLS)

## Zakres
Punkt 1 z listy przekazania: zaprojektowanie wydania, odnowienia i unieważnienia certyfikatu TLS zgodnego z Android Keystore i zaufanym CA. Wykonano wyłącznie projekt.

## Zmiany
- Dodano `docs/COMPANION_TLS_CERTIFICATE_LIFECYCLE_PL.md`.
- Kod aplikacji nie został zmieniony. Wersja aplikacji pozostaje 0.6.45 (versionCode 71).

## Walidacja
- Kod: brak zmian, nie dotyczy.
- Kompilacja i testy: **NOT RUN** (brak Kotlin, Gradle, Android SDK, sieci).

## Otwarte decyzje
1. Generowanie CSR: dodać zależność (np. BouncyCastle) czy kodować DER ręcznie. Wymaga decyzji użytkownika.
2. Składanie łańcucha w Keystore: potwierdzić na urządzeniu (pkt 5.1b).
3. Desktop CA: język i przechowywanie klucza root — poza zakresem tego kroku.

## Następny krok
Pkt 5.1b: czyste funkcje walidacji odpowiedzi CA (łańcuch, SAN, EKU, daty, zgodność klucza) z testami JVM na certyfikatach testowych. Start po zatwierdzeniu projektu i decyzji nr 1.
