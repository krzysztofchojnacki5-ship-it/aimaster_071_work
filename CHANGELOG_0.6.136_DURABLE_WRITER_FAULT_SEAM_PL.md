# CHANGELOG 0.6.136 — durable writer fault seam

## Cel
Przetestować granice trwałego zapisu istniejących writerów swap journal i patch receipt bez zmiany protokołu transakcji ani osłabienia atomowego commit.

## Implementacja
- Wydzielono wspólny internal `publishWorkspaceDurableFile()` wykorzystywany przez `writeSwapJournal()` i `writePatchReceipt()`.
- Publisher sam tworzy temp przez `createNewFile()`, ustala ownership, otwiera `FileOutputStream`, serializuje dane przez `DataOutputStream(BufferedOutputStream(...))`, wykonuje `flush()` i `fd.sync()`, publikuje wyłącznie przez `ATOMIC_MOVE`, próbuje best-effort directory sync i czyści tylko własny temp.
- Dodano per-call test seams: `flushAndSync`, `moveAtomically`, `syncDirectory`. Bez globalnych hooków, bez dodatkowego publicznego API i bez wpływu na normalne wywołania.
- Oba writery zachowują własne komunikaty dla `AtomicMoveNotSupportedException`.
- Patch receipt zachowuje dotychczasową kontrolę istniejącego receipt i transakcyjnie uzasadnione usunięcie stale temp.

## Testy
- Rzeczywisty `ProjectWorkspaceStore.kt` w host harness: 28/28 PASS.
- Dodane 9 fault scenarios: CREATE_NEW collision, serialization/write, flush, fd.sync, atomic move failure, brak obsługi ATOMIC_MOVE, directory sync failure, udany replace i kolejność directory sync.
- Pełna regresja: 219 host runtime PASS w 12 harnessach.
- Compile shim i source guards PASS.
- 2 testy JUnit publishera oraz 2 wcześniejsze ownership tests: ADDED / NOT RUN.
- Android/Gradle/Robolectric/APK/device/process-death/power-loss: NOT RUN (preflight BLOCKED).

## Gwarancje zachowane
- Brak nieatomowego fallbacku.
- Directory fsync best-effort; błąd po publikacji nie unieważnia zatwierdzonego pliku.
- Bez zmian AISW v1/v2, receipt format, commit point, public API, lock scope i Companion Transport status.
- App version `0.6.54`, code `80`; Companion Transport BLOCKED.
