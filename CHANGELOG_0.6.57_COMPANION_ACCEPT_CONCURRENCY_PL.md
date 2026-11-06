# CHANGELOG — 0.6.57-SOURCE

Data: 5 listopada 2026  
Baza: `AI_MASTER_PRO_0_6_56_PKT4_INTEGRATION_REVIEW_SOURCE.zip`  
Typ: poprawka źródłowa + test regresyjny; **nie wydanie APK**.

## Zakres
- Naprawiono współbieżność `CompanionPinnedTlsProfile.accept()` dla współdzielonego `SSLServerSocket`.
- Przypisanie `soTimeout` i wywołanie `accept()` są serializowane na monitorze konkretnego listenera. Timeout nie może zostać nadpisany przez równoległe wywołanie `accept()`.
- Handshake TLS odbywa się poza monitorem listenera, aby wolny peer nie blokował kolejnego przyjęcia połączenia.
- `closeServer()` usuwa listener z rejestru pod monitorem profilu, a fizycznie zamyka go poza tym monitorem i bez oczekiwania na monitor `accept`; zamknięcie może przerwać oczekujące `accept()`.
- Dodano test regresyjny loopback mTLS: pierwszy `accept` ma długi timeout, drugi krótszy; drugi nie może nadpisać timeoutu pierwszego.

## Walidacja wykonana
- `kotlinc -Werror` dla `CompanionSpkiSha256Pin.kt`, `CompanionTlsContext.kt` i `CompanionPinnedTlsProfile.kt`: PASS.
- Niezależny harness Kotlin/JVM wykorzystujący testowe certyfikaty loopback: PASS — pierwszy `accept` ukończył mTLS, drugi zachował własny timeout 200 ms i zakończył się `SocketTimeoutException`.
- Test JUnit dodany do `CompanionTlsContextTest.kt`, ale nie uruchomiony przez Gradle.

## Walidacja niewykonana
- Gradle/JUnit/Robolectric: NOT RUN (brak dostępnej lokalnie dystrybucji Gradle; wcześniejsza próba pobrania była blokowana przez DNS `services.gradle.org`).
- `assembleDebug`, APK, Android Keystore i test urządzeniowy: NOT RUN.

## Wersje
- `versionName=0.6.54`, `versionCode=80` — bez zmian.
- Nie utworzono APK 0.6.57.

## Status bramek
- R5 (współdzielony timeout listenera): poprawka źródłowa wykonana; harness loopback PASS; JUnit/Gradle nadal NOT RUN.
- Pkt 4: OPEN — pozostałe ryzyka R1–R4 i R6 nie są rozwiązane; brak integracji rejestru z ownerem/store.
- Pkt 5: PARTIAL.
- Pkt 6 transport: BLOCKED.
