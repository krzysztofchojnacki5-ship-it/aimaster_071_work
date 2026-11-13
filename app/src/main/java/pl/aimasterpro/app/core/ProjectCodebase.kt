package pl.aimasterpro.app.core

/**
 * Small, explicitly limited project context. This is metadata only, not a source-code index.
 * Do not claim that build/tests/files were inspected unless a tool actually did so.
 */
object ProjectCodebase {
    val repositorySummary: String = """
        === KONTEKST PROJEKTU AiFORTeca ANDROID ===
        - Aplikacja Android w Kotlinie; moduł aplikacji: app.
        - Architektura obejmuje MASTER, politykę routingu, silnik TeamRunEngine i ProviderClient.
        - To pole zawiera wyłącznie krótki opis architektury; rzeczywiste pliki źródłowe, jeśli zostały bezpiecznie odczytane, są dołączane w osobnym bloku kontekstu.
        - Nie zawiera automatycznie logów, wyników kompilacji ani wyników testów.
        - Agent może twierdzić, że przeanalizował tylko pliki jawnie wymienione i przekazane w bloku danych źródłowych.
    """.trimIndent()

    fun fullContext(): String = repositorySummary
}
