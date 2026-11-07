package pl.aimasterpro.app.core

data class ChatMessage(val id: String, val role: String, val text: String, val createdAt: Long = System.currentTimeMillis())

data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val enabled: Boolean = true,
    val parentProviderId: String = ""
)

enum class ProviderKind { OPENAI_COMPATIBLE, GEMINI, ANTHROPIC }

data class TokenUsageResult(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
    val reasoningTokens: Long,
    /** Tokens read from an existing prompt cache; subset of inputTokens. */
    val cachedTokens: Long,
    val status: String, // "REAL", "ESTIMATED", "UNAVAILABLE"
    /** Tokens written to the default (5-minute) prompt cache; subset of inputTokens. */
    val cacheWriteTokens: Long = 0L,
    /** Tokens written to a 1-hour prompt cache; subset of inputTokens. */
    val cacheWrite1hTokens: Long = 0L,
    /** Whether the provider explicitly returned the reasoning-token breakdown. */
    val reasoningTokensKnown: Boolean = false
)

data class ChatResponse(
    val text: String,
    val usage: TokenUsageResult,
    val providerId: String = "",
    val model: String = ""
)

enum class AgentRole(val label: String, val purpose: String, val systemPrompt: String) {
    MASTER("MASTER", "Koordynuje zadanie i scala wyniki", "Jesteś koordynatorem AI. Ustal cel, dziel złożone zadania na etapy, jasno oddzielaj fakty od założeń i nie twierdź, że wykonałeś działania, których nie wykonałeś."),
    ASSISTANT("Asystent Kodu", "Bezpośredni asystent AI do poprawiania błędów i pisania kodu", "Jesteś osobistym asystentem programistycznym AI. Rozmawiasz z użytkownikiem bezpośrednio, pomagasz mu w debugowaniu, pisaniu kodu w Kotlinie i Androidzie, analizie błędów kompilacji, Gradle oraz projektowaniu rozwiązań. Odpowiadaj konkretnie, podawaj gotowe fragmenty kodu i wyjaśniaj poprawki."),
    DEVELOPER("Programista", "Analizuje kod, architekturę i błędy", "Jesteś starszym inżynierem oprogramowania. Diagnozuj na podstawie dowodów. Wskazuj plik, symbol, przyczynę, poprawkę, ryzyko i test weryfikacyjny. Nie wymyślaj numerów linii ani wyników kompilacji."),
    DEBUGGER("Diagnosta", "Czyta logi i klasyfikuje awarie", "Jesteś diagnostą. Klasyfikuj błąd, wyodrębnij pierwszy istotny wyjątek, przyczynę pierwotną, skutki uboczne i kolejność napraw. Odróżniaj potwierdzone fakty od hipotez."),
    RESEARCHER("Badacz", "Bada dokumentację i porównuje rozwiązania", "Jesteś badaczem technicznym. Podawaj źródła, daty i niepewność. Nie udawaj dostępu do internetu, jeśli narzędzie wyszukiwania nie zostało uruchomione."),
    REVIEWER("Recenzent", "Sprawdza jakość i ryzyka", "Jesteś niezależnym recenzentem. Szukaj błędów, luk, regresji, ryzyk bezpieczeństwa i nieuzasadnionych założeń. Proponuj konkretne testy."),
    WRITER("Twórca", "Przygotowuje teksty i uporządkowane wyniki", "Twórz czytelne, dobrze ustrukturyzowane wyniki. Dobieraj format do zadania: listę, tabelę, instrukcję lub dokument.")
}

data class DiagnosticReport(
    val category: String,
    val summary: String,
    val evidence: String,
    val probableCause: String,
    val suggestedFix: String,
    val verification: String,
    val confidence: String = "Nieustalone"
)
