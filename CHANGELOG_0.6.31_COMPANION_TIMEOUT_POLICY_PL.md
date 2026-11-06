# Changelog 0.6.31 — polityka timeoutów Companion

## Zmiany
- Dodano `CompanionSessionTimeoutPolicy` z osobnymi limitami: handshake, bezczynność, transfer i twardy czas życia sesji.
- Polityka używa zegara monotonicznego (`System.nanoTime`) i nie zależy od zmiany daty/godziny systemowej.
- Aktywność odświeża wyłącznie idle timeout; hard lifetime pozostaje liczony od utworzenia polityki.
- Transfer ma niezależny deadline i pojedynczy slot; nie można go uruchomić przed zakończeniem fazy handshake.
- Timeouty są sprawdzane na granicy `elapsed >= limit`; test obejmuje zawinięcie licznika nanoTime.

## Testy
- 23/23 testów ukierunkowanego zestawu Companion PASS: frame protocol 10/10, wire codec 5/5, timeout policy 8/8.
- Kotlin/JVM `-Werror` dla nowej klasy i jej testów: PASS.

## Ograniczenia
- To nie jest uwierzytelnienie ani pełna maszyna stanów. `markHandshakeFinished()` nie dowodzi poprawnego parowania/TLS.
- Polityka nie jest jeszcze podłączona do odbiornika ani transportu; nie zamyka socketów/kluczy samodzielnie.
- TLS/mTLS, desktop Companion, transfer i OS-level sandbox nadal NOT IMPLEMENTED. `PROJECT_TESTS` pozostaje `NOT_RUN`.

## Wersja
- `versionCode = 57`
- `versionName = 0.6.31`
