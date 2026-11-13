# 0.6.88-SOURCE — trwały ledger dowodów

## Zmiany
- Dodano wersjonowany `VerificationEvidenceStore` z Base64URL, limitami rozmiaru i fail-closed odczytem.
- Konflikt ID jest odrzucany; identyczny zapis jest idempotentny; uszkodzony ledger nie jest automatycznie nadpisywany.
- Zapis/odczyt/modyfikacja są serializowane process-local przez wspólny monitor.
- Dodano Android adapter `SharedPreferences` z synchronicznym `commit()`.
- Ręczna weryfikacja patcha zapisuje strukturalne dowody i raportuje błąd, jeśli commit nie powiedzie się.

## Testy
- 8 testów store, 6 testów modelu dowodów, 3 testy ProjectTestRunner i 4 testy promptów: 21/21 PASS w lokalnych shim runnerach.
- Android backend skompilowany z atrapami API. Pełny Gradle/Android build NOT RUN.

## Ograniczenia
Ledger nie jest jeszcze czytany przez MASTER synthesis. Nie ma multi-process lock. Źródło `TOOL_EXECUTION` musi być w przyszłości tworzone wyłącznie przez hostową warstwę wykonawczą.
