# 0.6.70 — Companion Runtime Lifecycle

## Zmiany

- Dodano `CompanionRuntime`, który jest właścicielem dokładnie jednej instancji registry i jednego trust-revocation binding.
- Dodano `CompanionRuntimeSlot`, przeznaczony do utrzymywania w polu klasy Android `Application`; powtarzane `getOrCreate()` zwraca tę samą instancję.
- `close()` slotu usuwa referencję do runtime i zamyka binding. Binding czeka na cleanup-y fence, które już się rozpoczęły.
- Po zamknięciu runtime odrzuca dalsze użycie; slot może utworzyć nowy runtime i nie pozostawia starego fence listenera.
- Dodano trzy testy lifecycle.

## Walidacja

- `kotlinc -Werror`: PASS.
- Registry/publisher/binding: 36/36 PASS.
- Owner/TLS/gate: 25/25 PASS.
- Runtime: 3/3 PASS.
- Łącznie 64/64 metody lokalnego harnessu JVM PASS.

## Ważne ograniczenie

To nadal jest szkic w `drafts/pkt4`. Nie jest podłączony do `Application`, a `CompanionTrustService` nadal tworzy własny store. Nie deklarujemy zakończenia application-scoped lifecycle ani integracji produkcyjnej. Gradle/APK/Android device: NOT RUN.
