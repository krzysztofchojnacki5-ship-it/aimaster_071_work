# 0.6.71-SOURCE — Companion concurrent close hardening

- Naprawiono lifecycle race: równoległy drugi `close()` w bindingu/runtime nie może już wrócić, gdy pierwszy wciąż czeka na cleanup.
- Wprowadzono stany OPEN/CLOSING/CLOSED, oczekiwanie wszystkich zamykających i zapamiętanie błędu zamknięcia.
- Binding odłącza listener i czeka na rozpoczęte cleanup-y przed zakończeniem stanu CLOSED.
- Dodano testy równoległego zamykania z kontrolowanym blokowaniem socket cleanup.
- Walidacja lokalna: `kotlinc -Werror` PASS; 37/37 binding/registry/publisher + 4/4 runtime = 41/41.
- Nadal draft; brak integracji Application/store graph i brak APK/Gradle/Android testów.
