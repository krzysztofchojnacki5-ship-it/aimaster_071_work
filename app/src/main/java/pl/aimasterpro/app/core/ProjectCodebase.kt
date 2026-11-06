package pl.aimasterpro.app.core

/**
 * Small, explicitly limited project context. This is metadata only, not a source-code index.
 * Do not claim that build/tests/files were inspected unless a tool actually did so.
 */
object ProjectCodebase {
    val repositorySummary: String = """
        === KONTEKST PROJEKTU AI MASTER PRO ANDROID ===
        - Aplikacja Android w Kotlinie; moduł aplikacji: app.
        - Architektura obejmuje MASTER, politykę routingu, silnik TeamRunEngine i ProviderClient.
        - Kontekst przekazany w tym polu jest wyłącznie krótkim opisem architektury.
        - Nie zawiera automatycznie plików źródłowych, logów, wyników kompilacji ani wyników testów.
        - Agent musi wskazać brakujące pliki i nie może twierdzić, że je odczytał bez ich rzeczywistego przekazania.
    """.trimIndent()

    fun fullContext(): String = repositorySummary
}
