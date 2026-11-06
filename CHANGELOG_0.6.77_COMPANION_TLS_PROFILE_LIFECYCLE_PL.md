# Changelog 0.6.77-SOURCE — pełny lifecycle profilu TLS Companion

## Problem zamykany w tym etapie

W 0.6.76 owner i profil TLS były powiązane w `CompanionProvisionedSession`, ale sam profil nie miał jawnego, trwałego `close()`. Oznaczało to, że listener, handshake w toku, nieodebrany receipt lub aktywny socket mogły przetrwać zakończenie logicznej sesji, a registry mogło zachować uchwyt po zamknięciu profilu.

## Zmiany

1. `CompanionPinnedTlsProfile` implementuje `Closeable` i przechowuje stan permanentnego zamknięcia.
2. Profil śledzi wszystkie swoje sockety: handshake in-flight, receipt-pending i aktywne/admitted. Zamknięcie najpierw odcina nowe operacje, następnie zamyka listenery, wywołuje callbacki właścicieli połączeń, a na końcu wykonuje niezależny cleanup socketów.
3. Zamknięcie profilu anuluje expiry tasks, usuwa oczekujące receipts i zeruje przechowywane TLS session ID.
4. `CompanionTlsHandshakeGate.OwnedConnection` rejestruje callback zamknięcia profilu. Callback terminalnie zamyka ownera, socket, receipt i zwalnia uchwyt z registry.
5. Gate obsługuje wyścig pomiędzy zamknięciem profilu a utworzeniem/rejestracją wrappera. Nie może zarejestrować już zamkniętego uchwytu; nieudana konstrukcja wrappera uruchamia pełny cleanup ownera i socketu.
6. `CompanionProvisionedSession.close()` zamyka ownera i zawsze próbuje zamknąć dokładnie ten profil, z którym owner jest związany.
7. Store zamyka profil, jeśli konstrukcja ownera nie powiedzie się — zarówno w preferowanym factory, jak i w deprecated legacy factory.
8. `consume()` nie akceptuje receipt-u, którego socket jest już zamknięty.

## Walidacja

- `kotlinc -Werror` wszystkich produkcyjnych klas `Companion*.kt`: PASS.
- `kotlinc -Werror` wszystkich Companion core + wybranych testów gate/runtime/binding: PASS.
- `CompanionProvisioningStore.kt` skompilowany z lekkimi atrapami API Android/JSON: PASS (tylko seam kompilacyjny, bez runtime Keystore).
- Lokalny shim-JUnit runner: 16/16 PASS, w tym loopback mTLS i testy zamykania profilu.
- Manifest XML, statyczne call sites i integralność ZIP: PASS.
- Gradle/JUnit/Robolectric/assembleDebug/APK/Android Keystore/device: NOT RUN.

Nie uruchomiono transportu ani listenera automatycznie. Nie zmieniono `versionName=0.6.54` ani `versionCode=80`.
