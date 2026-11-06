# AI MASTER PRO 0.6.36 — jednorazowy dowód parowania Companion

## Zmiana

- Wynik `CompanionPairingAuthority.verify()` nadal udostępnia `accepted` i `code` wyłącznie jako informację diagnostyczną; sam boolean nie może być bramką autoryzacji sesji.
- Po poprawnym HMAC authority wydaje nieprzezroczysty `CompanionPairingReceipt` i rejestruje dokładnie ten obiekt według tożsamości referencji (`IdentityHashMap`).
- `consumeVerifiedPairing()` przyjmuje tylko receipt wydany przez tę samą instancję authority; obiekty podszywające się pod interfejs, receipt obcy lub ponownie użyty są odrzucane.
- Odczyt receipt jest jednorazowy i atomowy względem synchronizowanej authority. Wynik udostępnia wyłącznie metadane sesji/klienta/companion oraz monotoniczny czas weryfikacji; nie ujawnia PSK.
- Liczba nieodebranych receipt jest ograniczona do 32. Po osiągnięciu limitu `verify()` zwraca `RECEIPT_CAPACITY` i nie zużywa poprawnego oczekującego challenge; po odebraniu receipt można ponowić weryfikację.

## Testy regresyjne

- Forged `Verification(accepted=true)` i caller-created receipt nie mogą utworzyć zweryfikowanej tożsamości.
- Receipt wydany przez authority można odebrać dokładnie raz, także przy ośmiu równoległych próbach konsumpcji.
- Limit receipt jest egzekwowany i nie powoduje utraty prawidłowego challenge.
- Kotlin/JVM `-Werror`: 40/40 PASS; cały zestaw powtórzony 10 razy: 400/400 PASS.

## Granice bezpieczeństwa

Receipt jest capability API wewnątrz procesu i chroni przed przypadkowym/niepoprawnym użyciem publicznego boolean oraz podrobieniem obiektu przez zwykłego wywołującego. Nie jest granicą bezpieczeństwa przeciw złośliwemu kodowi działającemu w tym samym procesie, nie potwierdza tożsamości certyfikatu TLS, nie zapewnia poufności transportu i nie zastępuje OS sandboxa. Weryfikacja HMAC nadal opiera się na PSK dostarczonym poza kanałem.

## Status

- Kotlin/JVM wybranych modułów i testów: PASS.
- Android XML: PASS (parsowanie).
- Pełny Gradle/Robolectric, APK i urządzenie: NOT RUN.
- TLS/mTLS, provisioning, sockety, desktop Companion: NOT IMPLEMENTED.
- OS-level sandbox / `PROJECT_TESTS`: NOT IMPLEMENTED / NOT RUN.
