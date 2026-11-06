package pl.aimasterpro.app.core

enum class WorkMode(val label: String, val description: String, val systemInstruction: String) {
    PLANNING(
        "Planowanie",
        "Analiza projektu, rozpoznanie wymagań i plan bez wdrażania zmian.",
        """TRYB PLANOWANIE: badaj i planuj, nie wykonuj zmian. Najpierw rozpoznaj cel, ograniczenia, zależności i kryteria akceptacji. Wynik ma być kompletnym planem z etapami, właścicielami, ryzykami i testami. Nie deklaruj wdrożenia."""
    ),
    DIAGNOSTICS(
        "Diagnostyka",
        "Dochodzenie oparte na dowodach, bez modyfikowania projektu.",
        """TRYB DIAGNOSTYKA: prowadź analizę tylko do odczytu. Oddziel potwierdzone fakty od hipotez, wskaż dowody, przyczynę pierwotną, wpływ, poziom pewności i zalecane kroki. Nie deklaruj wykonania napraw, testów ani buildów."""
    ),
    AUTOMATION(
        "Automat",
        "Realizacja jawnie zleconego zadania, kontrola i weryfikacja.",
        """TRYB AUTOMAT: realizuj wyłącznie jawnie zlecony zakres. Dziel pracę na niezależne strumienie, kontroluj właścicieli i zależności, a wynik poddaj niezależnej weryfikacji. Nie wykonuj destrukcyjnych działań, publikacji, migracji ani płatnych integracji bez wymaganej zgody. Nie twierdź, że zmieniłeś pliki, jeśli nie masz narzędzia do ich edycji."""
    );

    val allowsWriteScopes: Boolean get() = this == AUTOMATION

    companion object {
        fun fromStored(value: String?): WorkMode = values().firstOrNull {
            it.name.equals(value, true) || it.label.equals(value, true)
        } ?: AUTOMATION
    }
}
