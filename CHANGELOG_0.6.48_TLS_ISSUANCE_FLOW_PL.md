# Changelog — AI MASTER PRO 0.6.48

## Punkt 5.1d — przepływ wydania leafa TLS
- `CompanionTlsIssuanceFlow`: czysta orkiestracja (generuj klucz → CSR → CA → walidacja → wynik). Zależności wstrzyknięte: `KeyProvider` (Keystore) i `CaChannel` (uwierzytelniony kanał pairing).
- Zasady fail-closed: istniejący alias nie jest nadpisywany (`ALIAS_EXISTS`); odpowiedź CA akceptowana tylko po `CompanionTlsLeafPolicy`; każda porażka po wygenerowaniu klucza usuwa alias; wyjście nie zawiera materiału prywatnego.
- `AndroidTlsKeyProvider` (`data/`): implementacja Keystore, klucz EC P-256 generowany w Keystore, podpis SHA256withECDSA, usuwanie aliasu. **NOT RUN** — wymaga urządzenia.
- Zmiana w walidatorze: brak. Przepływ używa domyślnych wymagań EKU (serverAuth i clientAuth) i limitu 30 dni.

## Fixtury EC (nowe, `companion-tls/ec/`)
- Wygenerowane openssl: `ec-root.pem`, `ec-leaf.pem` (+ klucz PKCS#8), `ec-rogue-root.pem`.
- `ec-leaf.pem` przechodzi `openssl verify`, ma SAN `companion-test`, EKU serverAuth i clientAuth, KeyUsage digitalSignature, CA:FALSE, żywotność dokładnie 30 dni.
- Istniejące fixtury w repozytorium są RSA-2048. Projekt wymaga EC P-256, więc dla przepływu dodano fixtury EC. Stare fixtury RSA pozostają bez zmian dla testów handshake.
- Klucz root nie został zachowany; fixtury nie są podpisywane ponownie.

## Testy
- `CompanionTlsIssuanceFlowTest` (7 testów): wydanie, obcy root, zły klucz, wygasły leaf, istniejący alias, błąd CA z usunięciem klucza, wersjonowanie aliasu.

## Granice
- Kod Kotlin nie został skompilowany ani uruchomiony (NOT RUN).
- Zapis łańcucha (publiczny) do prefs i jego odczyt — NIE zaimplementowane; to kolejny krok.
- Transport CA (kanał pairing) — NIE zaimplementowany; zgodnie z roadmapą transport jest zablokowany do zamknięcia bramek punktu 5.
- Składanie łańcucha w Keystore i użycie klucza Keystore w handshake TLS — do potwierdzenia na urządzeniu.
