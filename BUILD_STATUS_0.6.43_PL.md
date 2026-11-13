# AI MASTER PRO 0.6.43 — status wydania

## Zakres
Kontynuacja pozycji 5 listy Companion: bezpieczne powiązanie trwałego magazynu zaufania z TLS i właścicielem sesji.

## Zmiany
- Dodano `CompanionIdentityChallenge`: domenowo separowany komunikat `AI_MASTER_COMPANION_IDENTITY_CHALLENGE_V1\0`, limit challenge 1–4096 bajtów i weryfikację ECDSA/SHA-256 publicznym kluczem.
- `CompanionProvisioningStore.signChallenge()` podpisuje teraz komunikat z prefiksem domenowym. Ogranicza to ryzyko użycia podpisu z innego protokołu jako proof-of-possession Companion.
- Dodano `createPinnedTlsProfile(...)`: tworzy świeży `CompanionPinnedTlsProfile` z aktualnej, odszyfrowanej mapy peer ID → current/next SPKI. Brak provisionowanych peerów blokuje utworzenie profilu.
- Dodano preferowaną metodę `createSessionOwner(...)`: buduje profil z aktualnego snapshotu i wiąże go z nowym `CompanionSessionOwner`.
- Dokumentacja jasno wymaga osobnego TLS identity KeyStore z pełnym łańcuchem certyfikatów i SAN oraz trust store. Lokalny certyfikat generowany przez provisioning Keystore nie jest automatycznie certyfikatem TLS.
- Rotacja/unieważnienie wpływa na nowe profile. Istniejące sesje/profile wymagają jawnego zamknięcia i odtworzenia; automatyczne odcięcie aktywnych sesji nie jest jeszcze zaimplementowane.

## Zmienione pliki
- `app/src/main/java/pl/aimasterpro/app/core/CompanionIdentityChallenge.kt` (nowy)
- `app/src/main/java/pl/aimasterpro/app/data/CompanionProvisioningStore.kt`
- `app/src/test/java/pl/aimasterpro/app/core/CompanionIdentityChallengeTest.kt` (nowy)
- `app/build.gradle.kts` (versionCode 69, versionName 0.6.43)
- `PROJECT_WORKSPACE_ROADMAP_PL.md`
- `AGENT_HANDOFF_PROMPT_PL.md`
- `BUILD_STATUS_0.6.43_PL.md`
- `CHANGELOG_0.6.43_COMPANION_PROVISIONING_TLS_INTEGRATION_PL.md`

## Walidacja
- `CompanionIdentityChallengeTest`: **2/2 testy, 5/5 asercji PASS** (podpis poprawny, zmieniony challenge, zmieniony podpis, pusty challenge, za duży challenge).
- Kotlin `-Werror`: `CompanionProvisioningStore` + `CompanionPinnedTlsProfile` + `CompanionSessionOwner` i wymagane klasy core skompilowane z lokalnymi atrapami Android/JSON: **PASS**. To kontrola składni i typów; atrapy nie testują zachowania platformy.
- ZIP integrity: sprawdzona przed publikacją.
- Pełny Gradle/Robolectric: **NOT RUN** (wrapper wymaga pobrania Gradle z `services.gradle.org`, DNS/network unavailable).
- Android Keystore na urządzeniu/emulatorze, APK, instalacja i test urządzeniowy: **NOT RUN**.
- `PROJECT_TESTS`: **NOT RUN**.

## Otwarte bramki
- Certyfikat TLS z zaufanym łańcuchem i prawidłowym SAN oraz proces jego wydania/provisioningu.
- Bezpieczna migracja istniejącego PSK.
- Recovery/re-pairing po utracie klucza lub urządzenia.
- Egzekwowane zamykanie aktywnych profili/sesji po revocation; obecnie snapshot starego profilu pozostaje ważny do zamknięcia.
- Testy integracyjne na prawdziwym Android Keystore.
- Dopiero po zamknięciu tych bramek wolno rozpocząć transport Companion.

## Pochodzenie źródeł
Kontynuacja bazy 0.6.41/0.6.42 opartej na rekonstrukcji 0.6.40 z odzyskanego 0.6.39. Nie jest to oryginalny odzyskany ZIP 0.6.40.
