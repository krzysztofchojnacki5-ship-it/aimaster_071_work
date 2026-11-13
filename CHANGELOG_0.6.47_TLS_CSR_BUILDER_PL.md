# Changelog — AI MASTER PRO 0.6.47

## Punkt 5.1c — generowanie CSR (PKCS#10) dla leafa TLS
- Dodano `CompanionCsrBuilder`: koduje CSR w DER ręcznie (bez nowej zależności). Żądane rozszerzenia: SAN (dokładne nazwy DNS), KeyUsage digitalSignature, EKU serverAuth i clientAuth. CA decyduje, czy je przyjąć.
- Podpis jest wykonywany przez wstrzyknięty callback, więc klucz prywatny pozostaje w Android Keystore. Builder weryfikuje podpis kluczem publicznym przed zwróceniem CSR i kończy się błędem, jeśli się nie zgadza (fail-closed).
- Walidacja wejścia: CN, nazwy DNS (lowercase, bez wildcardów, bez duplikatów, limity długości i liczby), klucz EC.
- Dodano `CompanionCsrBuilderTest` (7 testów).
- Dodano prototyp w Pythonie `docs/tools/csr_proto.py`, którym zweryfikowano ten sam algorytm kodowania.

## Weryfikacja algorytmu (prototyp + openssl)
- Wygenerowany CSR przechodzi `openssl req -verify` (komunikat „self-signature verify OK”). `openssl req -text` poprawnie odczytuje SAN, KeyUsage i EKU.
- Zmodyfikowany bajt w treści CN powoduje „self-signature verify failure”.
- Uwaga: `openssl req -verify` zwraca kod 0 także przy błędzie. Wynik należy odczytywać z komunikatu, nie z kodu wyjścia.

## Granice
- Kod Kotlin nie został skompilowany ani uruchomiony (NOT RUN). Prototyp potwierdza algorytm, a nie implementację w Kotlinie.
- Decyzja nr 1 (zależność vs DER ręcznie): rozstrzygnięto na korzyść DER ręcznego, zgodnie z propozycją. Wymaga akceptacji użytkownika.
- Składanie łańcucha w Keystore nadal do potwierdzenia na urządzeniu.
