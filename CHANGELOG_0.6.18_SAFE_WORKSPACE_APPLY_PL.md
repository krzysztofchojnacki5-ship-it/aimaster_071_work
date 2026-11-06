# AI MASTER PRO 0.6.18 — jawna zgoda i bezpieczny zapis workspace

## Nowe elementy
- `ProjectWorkspaceStore.applyPreview` stosuje wyłącznie zaakceptowany preview do prywatnego workspace aplikacji. Nie modyfikuje oryginalnego ZIP-a ani zainstalowanego kodu aplikacji.
- Przed zapisem ponownie sprawdza integralność całego workspace, bazowe SHA-256 każdego pliku, brak kolizji ścieżek (również case-insensitive), operacje ADD/MODIFY/DELETE, wynikowe SHA-256, UTF-8, NUL, limity rozmiarów i dozwolone rozszerzenia tekstowe.
- Dodatkowa walidacja `ChangeSafetyGuard` blokuje sekrety, chronione ścieżki i niebezpieczne nazwy.
- Tworzony jest snapshot przed zmianą. Cały workspace jest kopiowany do stagingu, modyfikowany poza aktywnym katalogiem, otrzymuje nowy manifest SHA-256 i dopiero potem przechodzi przez transakcyjne przełączenie katalogu.
- Po Peer Review z dwoma pozytywnymi werdyktami użytkownik musi dwukrotnie jawnie zatwierdzić zapis. W przypadku niezgodnego stanu workspace patch zostaje odrzucony jako nieaktualny.
- `ChangeSetStore` rejestruje identyfikator snapshotu i stan `APPLIED_UNVERIFIED`. Stan `APPLIED` nadal wymaga PASS testów; test runner i rollback należą do 0.6.19.

## Naprawy wykryte po drodze
- Usunięto zdublowaną deklarację lokalnej zmiennej `symlink` w `ProjectWorkspaceStore.verifyCurrentWorkspace`, która powodowała błąd kompilacji Kotlin.
- Zapis patcha nie pozwala usunąć ostatniego pliku workspace; nieudana operacja usuwa nieużyty snapshot i staging.
- Patch nie może dodawać plików binarnych ani nieobsługiwanych rozszerzeń.

## Weryfikacja
- Czysta kompilacja Kotlin/JVM: `ProjectWorkspaceStore` + `ProjectPatchEngine` + `ChangeSafetyGuard` — PASS.
- Testy lokalne JVM dla `PatchPeerReviewEngine`, `PatchPeerReviewPlanner` i rzeczywistego `ProjectWorkspaceStore.applyPreview`: **28/28 PASS**. Test runner używa lekkiego stubu JUnit, bez Gradle.
- MainActivity: skan składni Kotlin bez diagnostyk parsera; pełne sprawdzenie typów Android UI nie było możliwe bez Android SDK i zależności Gradle.
- Testy Robolectric `ChangeSetStore` i pełna suite Gradle — NOT RUN.
- Pełny Android build / APK — NOT RUN.
