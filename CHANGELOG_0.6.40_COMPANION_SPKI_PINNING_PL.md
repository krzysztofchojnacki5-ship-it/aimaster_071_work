# Changelog 0.6.40 — Companion SPKI pinning po walidacji PKIX

- Dodano `CompanionSpkiSha256Pin`: niezmienny pin SHA-256 liczony z DER `SubjectPublicKeyInfo` certyfikatu X.509.
- Parser przyjmuje wyłącznie kanoniczne 64 znaki małego hex; odrzuca wielkie litery, nieprawidłową długość i inne znaki.
- Dodano `createPinnedMutualTlsContext(...)`. Konfiguracja wymaga niepustego zbioru jawnych pinów peer.
- Walidacja jest dwuwarstwowa: najpierw standardowa walidacja łańcucha PKIX przez platformowy `TrustManagerFactory`, następnie dopasowanie SPKI certyfikatu końcowego do jednego z dozwolonych pinów.
- Obsłużono ścieżki `X509ExtendedTrustManager` dla zwykłych wywołań, socketów i `SSLEngine`; jeśli dostawca udostępnia rozszerzony manager, zachowana jest jego walidacja kontekstowa.
- Zbiór wielu pinów pozwala na kontrolowane okno obecnego i następnego klucza. Nie dodano jeszcze mechanizmu provisioningu, rotacji ani unieważniania.
- Zachowano dotychczasowe `createMutualTlsContext(...)` jako nieprzypinany wariant bazowy do testów/kompatybilności; nie należy używać go jako profilu produkcyjnego Companion.
- Dodano testy rzeczywistego handshake loopback: poprawne piny klienta i serwera, błędny pin serwera, błędny pin klienta, akceptacja zestawu current+next oraz odrzucenie pustego/niekanonicznego pinu.

## Weryfikacja

- Wybrane testy Kotlin/JVM z `kotlinc -Werror` i lekkim runnerem testowym: 15/15 PASS.
- Powtórzenia: 7 × 15/15 = 105/105 PASS.
- Są to testy JVM z rzeczywistym handshake TLS/mTLS na loopback; nie są to testy Gradle/Robolectric ani testy urządzenia Android.
- Pełny Gradle/Robolectric: NOT RUN.
- APK / instalacja / test urządzenia: NOT RUN.
- Provisioning certyfikatów, bezpieczne przechowywanie pinów, rotacja/unieważnianie: NOT IMPLEMENTED.
- Tożsamość peer związana z `CompanionSessionOwner`: NOT IMPLEMENTED.
- Transport Companion / Desktop Companion: NOT IMPLEMENTED.
- OS-level sandbox: NOT IMPLEMENTED.
- `PROJECT_TESTS`: NOT RUN.

## Pochodzenie

Ta paczka została odtworzona na bazie odzyskanej paczki źródłowej 0.6.39, ponieważ oryginalne archiwum 0.6.40 w ścieżce projektu nie udostępniało bajtów do materializacji. Nie jest to bit-po-bicie oryginalny ZIP 0.6.40; jest to jawnie oznaczona rekonstrukcja funkcji SPKI pinning na bazie 0.6.39.
