# 0.6.91-SOURCE — host-owned ToolExecutionRegistry

- Dodano registry generujący executionId po stronie aplikacji i wydający nieprzezroczyste uchwyty invocation.
- `begin()` utrwala NOT_RUN przed przekazaniem uchwytu adapterowi; błąd zapisu blokuje start.
- `complete()` wymaga uchwytu z tej samej instancji registry, przyjmuje PASS/FAIL i działa jednokrotnie.
- `abandon()` zamyka wywołanie jako NOT_RUN.
- Błąd zapisu wyniku nie zwraca PASS; trwały marker NOT_RUN pozostaje.
- Testy obejmują foreign handle, double completion, sink failure, abandon i race complete-vs-abandon.
- Testy Evidence/Store/Registry 25/25 PASS; ProjectTestRunner 3/3 PASS; razem 28/28 lokalnych shim testów.
- Registry nie jest jeszcze podłączony do adapterów narzędzi; Gradle/Android build NOT RUN.
