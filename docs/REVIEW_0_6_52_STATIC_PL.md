# Przegląd statyczny 0.6.52 — bez uruchamiania

Zakres: kod Companion i powiązane zmiany w `SecureStore`, `MainActivity` i importach od 0.6.45 do 0.6.52.
Metoda: czytanie kodu, sprawdzanie nazw, arytmetyki testów, zgodności dokumentacji z kodem. NIE jest to kompilacja ani wynik testów.

## 1. Co zostało sprawdzone i jest zgodne
- Test timeoutu bezczynności: zegar w milisekundach, polityka w nanosekundach (`readNanos = millis * 1_000_000`), 21 s > 20 s bezczynności. Logika poprawna.
- Każda klasa testowa ma liczbę testów zgodną z dokumentacją, po poprawce 0.6.47 i 0.6.49 (CSR 7, chain 13, PSK 9, recovery 10, flow 7, leaf policy 12).
- Brak `!!` w zmienionym kodzie produkcyjnym.
- Brak `apply()` tam, gdzie wynik zapisu ma znaczenie (SecureStore, blob store, magazyn pinów używają `commit()`).
- Walidacja daty leafa jest spójna między `CompanionTlsLeafPolicy` a testami (`checkValidity` z tym samym czasem).

## 2. Ustalenia do poprawy (nie naprawiono w tym przeglądzie)
| Ważność | Ustalenie | Gdzie |
|---|---|---|
| Krytyczna | Cały kod 0.6.45–0.6.52 nie został skompilowany ani uruchomiony. Najpierw `./gradlew testDebugUnitTest`. | wszędzie |
| Wysoka | Otwarte połączenie działa po unieważnieniu peera do następnej operacji (pkt 4, projekt bez kodu). | `CompanionSessionOwner`, `CompanionPinnedTlsProfile` |
| Wysoka | Brak ochrony przed cofnięciem całego magazynu pinów (rollback). Usunięcie samych pinów przy zachowanym PSK jest wykrywane, pełne cofnięcie nie. | `CompanionProvisioningStore` |
| Średnia | Komunikat importu mówi „poprzednia konfiguracja została zachowana”, ale przy błędzie w połowie konfiguracje o tych samych ID mogą być już nadpisane. Komunikat jest zbyt optymistyczny. | `MainActivity` (import), `SecureStore.replaceAllConfigs` |
| Średnia | Trwale unieważniony klucz Keystore dla `SecureStore` blokuje zapis i odczyt kluczy API. Nie ma automatycznej ścieżki naprawy. Decyzja projektowa do podjęcia. | `SecureStore.key()` |
| Średnia | Zapisy `CompanionPskRepository` i `AndroidTlsChainBlobStore` mają wynik, ale wywołujący w produkcji jeszcze ich nie używa (Companion nie jest w przepływie pairingu). | `CompanionTrustService` |
| Niska | Changelogi 0.6.46, 0.6.48–0.6.51 i statusy używają etykiety „PASS na papierze”. To przegląd bez wykonania, nie wynik testu. Należy czytać je jako „NOT RUN”. | `BUILD_STATUS_*`, `CHANGELOG_*` |
| Niska | Fixtury EC mają ważność 30 dni od wygenerowania. Testy liczą czas od `notBefore`, więc nie wygasają, ale nie należy dodawać testów opartych na czasie systemowym. | `test/resources/companion-tls/ec` |

## 3. Co trzeba sprawdzić na urządzeniu (nie da się statycznie)
- Czy `AndroidKeyStore` zaakceptuje klucz EC z `PURPOSE_SIGN` w przepływie CSR.
- Czy `SecureStore` odczytuje klucze API po restarcie aplikacji.
- Czy karta „Zaufanie Companion” renderuje się poprawnie w Diagnostyce.
- Czy `wipeAllTrust` i `deleteAllTlsKeys` działają na prawdziwym Keystore.
