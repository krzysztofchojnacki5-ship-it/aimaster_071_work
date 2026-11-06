# PKT 4 — archiwum historycznych szkiców

**Uwaga:** ten katalog nie jest już źródłem prawdy dla elementów promowanych do produkcyjnego source set. Aktualne implementacje i testy znajdują się w `app/src/main/java/pl/aimasterpro/app/core/` oraz `app/src/test/java/pl/aimasterpro/app/core/`. Pliki poniżej zachowano jako materiał historyczny z etapów 0.6.55–0.6.74; nie kopiować ich ponownie do aplikacji.

## Stan po 0.6.75

W produkcyjnym source set znajdują się:
- `CompanionConnectionRegistry` — identity-based ownership, jednorazowe bilety, epoch fence, revocation i permanent shutdown.
- `CompanionTrustRevocationBinding` — lifecycle-safe połączenie źródła zmian zaufania z registry.
- `CompanionRuntime` / `CompanionRuntimeSlot` — jeden runtime na Application; shutdown zamyka registry, aktywne handle i odpina listener.
- `CompanionTlsHandshakeGate` — ticket przed TLS handshake, aktywacja ownera z receipt przypiętego profilu, rejestracja `OwnedConnection` po handshake.

`CompanionApplication` posiada wspólny `CompanionProvisioningStore` i runtime. `companionTlsHandshakeGate()` zwraca bramkę z tego samego runtime. Żaden listener ani transport nie uruchamia się automatycznie.

## Pozostałe pliki w tym katalogu

- `CompanionApplicationGraph.kt` i test — historyczny szkic graphu; rzeczywistym właścicielem jest `CompanionApplication`.
- `CompanionConnectionRegistry.kt` / test — historyczna kopia sprzed promocji produkcyjnej.
- `CompanionRuntime.kt` / test — historyczna kopia sprzed promocji produkcyjnej i późniejszego shutdown hardening.
- `CompanionTlsHandshakeGate.kt` — historyczna kopia; aktualna implementacja jest w `app/src/main/java`.
- `CompanionTrustRevocationBinding.kt` / test — historyczna kopia sprzed promocji produkcyjnej.

## Walidacja 0.6.75

- `kotlinc -Werror` wybranych klas TLS/owner/registry/gate/binding/runtime: PASS.
- Lokalny shim runner: nowy gate/runtime 8/8 PASS; istniejący registry/binding/runtime 35/35 PASS.
- Loopback mTLS obejmuje przyjęcie, aktywację, revocation oraz wyścig revocation pomiędzy handshake/activation a register.
- Runtime shutdown odrzuca nowe tickety, unieważnia wcześniej pobrane i zamyka aktywne handle.
- Gradle/JUnit/Robolectric/assembleDebug/APK/Android Keystore/device: NOT RUN.

Szczegóły: `BUILD_STATUS_0.6.75_PL.md`, `CHANGELOG_0.6.75_COMPANION_TLS_GATE_PRODUCTION_PL.md`.

## Status 0.6.76
- `CompanionProvisionedSession` i `CompanionProvisioningStore.createSessionProvision()` tworzą zalecaną, nierozdzielną parę profilu TLS i ownera.
- `CompanionTlsHandshakeGate` przyjmuje capability; legacy profile+owner overload odrzuca obcy profil przed admission ticket.
- Aktualne klasy są w produkcyjnym source set. Ten README opisuje tylko kontekst i historyczne drafty.


## Status 0.6.77 — lifecycle profilu TLS
`CompanionPinnedTlsProfile` oraz `CompanionProvisionedSession` są już produkcyjne i nie należy ich kopiować z draftów. Profil jest `Closeable`, śledzi listener/socket/receipt, a jego zamknięcie wywołuje callbacki `OwnedConnection`, które zamykają ownera i zwalniają registry. `CompanionProvisionedSession.close()` zamyka ownera oraz sparowany profil. Testy lokalnego shim-JUnit: 16/16 PASS; pełny Gradle/Android NOT RUN. Szczegóły w `BUILD_STATUS_0.6.77_PL.md`.
