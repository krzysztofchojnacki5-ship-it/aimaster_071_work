package pl.aimasterpro.app.agents

import java.util.Locale

data class AgentSpec(
    val id: String,
    val name: String,
    val category: String,
    val responsibility: String,
    val keywords: List<String>,
    val systemPromptOverride: String = ""
) {
    // Custom profile instructions are additive; the same baseline guardrails apply to every agent.
    val systemPrompt: String
        get() = buildString {
            append("Jesteś agentem $name. Obszar: $category. Zakres: $responsibility.\n")
            if (systemPromptOverride.isNotBlank()) {
                append("Dodatkowe instrukcje profilu:\n").append(systemPromptOverride.trim()).append("\n")
            }
            append("Działaj wyłącznie w przydzielonym zakresie; nie zwracaj się do użytkownika ani nie deleguj dalej. ")
            append("Korzystaj tylko z dostarczonych danych i narzędzi. Nie zmyślaj odczytanych plików, źródeł, testów ani wykonanych działań. ")
            append("Oddziel fakty, założenia i niewiadome. Nie ujawniaj kluczy API, haseł ani sekretów.\n")
            append("Zwróć zwięzły raport w języku polecenia: Status; Ustalenia; Dowody; Zmienione pliki; Weryfikacja; Ryzyka/blokady; Następny krok.")
        }
}

object AgentCatalog {
    data class AgentMatch(val agent: AgentSpec, val keywordScore: Int)

    val all: List<AgentSpec> = listOf(

        AgentSpec("lead-coordinator", "Lead Coordinator", "Koordynacja i odkrywanie produktu", "Rozumie polecenie, przydziela właścicieli, koordynuje pracę i scala zweryfikowane wyniki.", listOf("koordyn", "master", "zespół", "zadanie", "projekt")),
        AgentSpec("planning-lead", "Planning Lead", "Koordynacja i odkrywanie produktu", "Prowadzi analizę tylko do odczytu, doprecyzowanie wymagań i plan dostarczenia.", listOf("plan", "harmonogram", "etap", "zakres")),
        AgentSpec("diagnostics-lead", "Diagnostics Lead", "Koordynacja i odkrywanie produktu", "Koordynuje dochodzenie oparte na dowodach, bez zmian w projekcie.", listOf("diagno", "błąd", "awaria", "log", "crash")),
        AgentSpec("delivery-lead", "Delivery Lead", "Koordynacja i odkrywanie produktu", "Prowadzi realizację jawnie zatwierdzonego zakresu aż do weryfikacji.", listOf("wdroż", "realiz", "implement", "napraw")),
        AgentSpec("stream-coordinator", "Stream Coordinator", "Koordynacja i odkrywanie produktu", "Śledzi zależności, przekazania i postęp równoległych strumieni pracy.", listOf("równoleg", "zależno", "postęp", "przekaz")),
        AgentSpec("scope-risk", "Scope & Risk", "Koordynacja i odkrywanie produktu", "Kontroluje granice zakresu, zgody, ryzyka i bezpieczne warunki wykonania.", listOf("ryzyko", "bezpie", "zgoda", "zakres")),
        AgentSpec("critical-reviewer", "Critical Reviewer", "Koordynacja i odkrywanie produktu", "Podważa założenia i niezależnie ocenia plan lub zakończoną pracę.", listOf("review", "recenz", "kontrola", "jakość", "błędy")),
        AgentSpec("product-strategist", "Product Strategist", "Koordynacja i odkrywanie produktu", "Łączy wartość dla użytkownika, kierunek produktu i mierzalne rezultaty.", listOf("produkt", "strateg", "wartość", "cel")),
        AgentSpec("user-research", "User Research", "Koordynacja i odkrywanie produktu", "Rozpoznaje grupy użytkowników, ich potrzeby i dowody dotyczące problemu.", listOf("użytkownik", "potrzeby", "badanie", "ankiet")),
        AgentSpec("business-analyst", "Business Analyst", "Koordynacja i odkrywanie produktu", "Analizuje cele biznesowe, procesy i oczekiwaną wartość.", listOf("biznes", "koszt", "proces", "opłacal")),
        AgentSpec("domain-analyst", "Domain Analyst", "Koordynacja i odkrywanie produktu", "Analizuje pojęcia domenowe, reguły i ograniczenia dziedziny.", listOf("domena", "reguły", "logika biznes")),
        AgentSpec("requirements-analyst", "Requirements Analyst", "Koordynacja i odkrywanie produktu", "Przekształca cele w testowalne wymagania funkcjonalne i niefunkcjonalne.", listOf("wymag", "specyfik", "kryteria", "funkcj")),
        AgentSpec("portfolio-analyst", "Portfolio Analyst", "Koordynacja i odkrywanie produktu", "Ocenia priorytety, zależności i kompromisy między inicjatywami.", listOf("priorytet", "portfolio", "roadmap")),
        AgentSpec("stakeholder-analyst", "Stakeholder Analyst", "Koordynacja i odkrywanie produktu", "Mapuje interesariuszy, odpowiedzialności i sprzeczne potrzeby.", listOf("interesarius", "odpowiedzial")),
        AgentSpec("repository-researcher", "Repository Researcher", "Badania, projektowanie i architektura", "Mapuje strukturę repozytorium, konwencje i istniejącą implementację.", listOf("repo", "plik", "kod", "struktura", "android studio", "projekt")),
        AgentSpec("docs-researcher", "Docs Researcher", "Badania, projektowanie i architektura", "Wyszukuje i ocenia dokumentację produktu oraz dokumentację techniczną.", listOf("dokumentac", "instrukcj", "manual", "readme")),
        AgentSpec("api-researcher", "API Researcher", "Badania, projektowanie i architektura", "Bada możliwości i ograniczenia API oraz platform.", listOf("api", "endpoint", "rest", "http", "integrac")),
        AgentSpec("dependency-researcher", "Dependency Researcher", "Badania, projektowanie i architektura", "Ocenia pakiety, wersje, utrzymanie i ryzyka zależności.", listOf("gradle", "bibliotek", "dependency", "zależno", "pakiet")),
        AgentSpec("solutions-researcher", "Solutions Researcher", "Badania, projektowanie i architektura", "Znajduje istniejące podejścia i porównuje alternatywne rozwiązania.", listOf("rozwiąz", "alternatyw", "porówn", "metod")),
        AgentSpec("source-verifier", "Source Verifier", "Badania, projektowanie i architektura", "Weryfikuje twierdzenia i wiąże je z wiarygodnymi źródłami.", listOf("źródło", "weryfik", "fakt", "link")),
        AgentSpec("benchmark-analyst", "Benchmark Analyst", "Badania, projektowanie i architektura", "Definiuje porównania i interpretuje wyniki benchmarków.", listOf("benchmark", "wydaj", "pomiar", "porówn")),
        AgentSpec("ux-designer", "UX Designer", "Badania, projektowanie i architektura", "Projektuje przepływy użytkownika i zachowanie interakcji.", listOf("ux", "przepływ", "ergonom", "użytecz")),
        AgentSpec("ui-designer", "UI Designer", "Badania, projektowanie i architektura", "Definiuje prezentację interfejsu i wzorce systemu projektowego.", listOf("ui", "interfejs", "wygląd", "kolor", "ekran")),
        AgentSpec("ia-designer", "IA Designer", "Badania, projektowanie i architektura", "Strukturyzuje informacje, nawigację i hierarchię treści.", listOf("nawigac", "menu", "informac", "hierarch")),
        AgentSpec("accessibility-specialist", "Accessibility Specialist", "Badania, projektowanie i architektura", "Ocenia dostępność i inkluzywność interakcji.", listOf("dostęp", "accessibility", "czytnik", "kontrast")),
        AgentSpec("prototype-designer", "Prototype Designer", "Badania, projektowanie i architektura", "Buduje prototypy pozwalające testować proponowane interakcje.", listOf("prototyp", "makiet", "demo")),
        AgentSpec("usability-researcher", "Usability Researcher", "Badania, projektowanie i architektura", "Ocenia użyteczność i identyfikuje problemy interakcji.", listOf("użytecz", "test użytk", "nawigac")),
        AgentSpec("systems-architect", "Systems Architect", "Badania, projektowanie i architektura", "Projektuje granice systemu, komponenty i zachowanie między systemami.", listOf("architektur", "system", "komponent")),
        AgentSpec("app-architect", "App Architect", "Badania, projektowanie i architektura", "Definiuje strukturę aplikacji i podejście implementacyjne.", listOf("android", "aplikac", "moduł", "kotlin")),
        AgentSpec("frontend-engineer", "Frontend Engineer", "Inżynieria i integracja", "Implementuje interfejsy webowe i zachowanie po stronie klienta.", listOf("frontend", "web", "html", "css", "javascript")),
        AgentSpec("backend-engineer", "Backend Engineer", "Inżynieria i integracja", "Implementuje usługi serwerowe i logikę biznesową.", listOf("backend", "serwer", "usługa", "endpoint")),
        AgentSpec("api-engineer", "API Engineer", "Inżynieria i integracja", "Projektuje i implementuje kontrakty API oraz integracje.", listOf("api", "integrac", "json", "http")),
        AgentSpec("database-specialist", "Database Specialist", "Inżynieria i integracja", "Projektuje modele danych, zapytania i zachowanie bazy danych.", listOf("baza", "sqlite", "database", "dane", "migrac")),
        AgentSpec("mobile-engineer", "Mobile Engineer", "Inżynieria i integracja", "Implementuje i ocenia zachowanie aplikacji mobilnych.", listOf("android", "mobile", "kotlin", "telefon", "apk")),
        AgentSpec("desktop-engineer", "Desktop Engineer", "Inżynieria i integracja", "Pracuje nad aplikacjami desktopowymi i interfejsem terminalowym.", listOf("desktop", "windows", "terminal", "cli")),
        AgentSpec("integration-engineer", "Integration Engineer", "Inżynieria i integracja", "Łączy komponenty i systemy zewnętrzne.", listOf("integrac", "połączen", "provider", "oauth")),
        AgentSpec("ai-engineer", "AI Engineer", "Inżynieria i integracja", "Implementuje i ocenia funkcje AI/LLM oraz przepływy agentowe.", listOf("ai", "agent", "model", "llm", "token", "prompt", "ollama")),
        AgentSpec("performance-engineer", "Performance Engineer", "Inżynieria i integracja", "Mierzy i poprawia wydajność działania oraz zużycie zasobów.", listOf("wydaj", "szybko", "pamięć", "cpu", "optymaliz", "token")),
        AgentSpec("refactoring-engineer", "Refactoring Engineer", "Inżynieria i integracja", "Poprawia strukturę kodu, zachowując dotychczasowe zachowanie.", listOf("refactor", "porządek", "struktura kodu", "duplik")),
        AgentSpec("qa-architect", "QA Architect", "Jakość, bezpieczeństwo i zgodność", "Definiuje strategię jakości, pokrycie testami i kryteria odbioru.", listOf("qa", "testy", "jakość", "odbiór")),
        AgentSpec("unit-test-engineer", "Unit Test Engineer", "Jakość, bezpieczeństwo i zgodność", "Dodaje ukierunkowane testy jednostkowe i przypadki brzegowe.", listOf("unit", "jednostk", "test")),
        AgentSpec("integration-test-engineer", "Integration Test Engineer", "Jakość, bezpieczeństwo i zgodność", "Weryfikuje współdziałanie komponentów i usług.", listOf("test integr", "integrac", "komponent")),
        AgentSpec("e2e-test-engineer", "E2E Test Engineer", "Jakość, bezpieczeństwo i zgodność", "Testuje kompletne przepływy użytkownika i systemu.", listOf("e2e", "end to end", "cały przepływ")),
        AgentSpec("regression-analyst", "Regression Analyst", "Jakość, bezpieczeństwo i zgodność", "Wykrywa regresje względem istniejącego zachowania.", listOf("regres", "stare funkcje", "po aktualizacji")),
        AgentSpec("reliability-engineer", "Reliability Engineer", "Jakość, bezpieczeństwo i zgodność", "Ocenia obsługę awarii, odporność i odzyskiwanie działania.", listOf("awaria", "stabil", "recovery", "wznow", "timeout")),
        AgentSpec("implementation-reviewer", "Implementation Reviewer", "Jakość, bezpieczeństwo i zgodność", "Sprawdza implementację względem wymagań i konwencji kodu.", listOf("review", "implement", "kod", "wymag")),
        AgentSpec("threat-analyst", "Threat Analyst", "Jakość, bezpieczeństwo i zgodność", "Modeluje zagrożenia i identyfikuje ryzyka bezpieczeństwa.", listOf("zagroż", "threat", "atak", "bezpieczeń")),
        AgentSpec("app-security-auditor", "App Security Auditor", "Jakość, bezpieczeństwo i zgodność", "Ocenia kod aplikacji i zabezpieczenia pod kątem słabości.", listOf("security", "bezpieczeń", "klucz api", "sekret")),
        AgentSpec("privacy-specialist", "Privacy Specialist", "Jakość, bezpieczeństwo i zgodność", "Ocenia przetwarzanie danych osobowych i ryzyka prywatności.", listOf("prywat", "dane osob", "rodo")),
        AgentSpec("license-analyst", "License Analyst", "Jakość, bezpieczeństwo i zgodność", "Sprawdza pochodzenie kodu i treści oraz obowiązki licencyjne.", listOf("licenc", "mit", "copyright", "open source")),
        AgentSpec("supply-chain-auditor", "Supply Chain Auditor", "Jakość, bezpieczeństwo i zgodność", "Ocenia ryzyka integralności zależności i łańcucha dostaw.", listOf("supply chain", "zależno", "bibliotek", "package")),
        AgentSpec("compliance-analyst", "Compliance Analyst", "Jakość, bezpieczeństwo i zgodność", "Mapuje wymagania zgodności i potrzebne dowody.", listOf("compliance", "zgodność", "regulac")),
        AgentSpec("devops-engineer", "DevOps Engineer", "Operacje, dokumentacja i wsparcie", "Usprawnia procesy developmentu, wdrożeń i operacji.", listOf("devops", "deploy", "build", "release")),
        AgentSpec("ci-engineer", "CI Engineer", "Operacje, dokumentacja i wsparcie", "Projektuje i utrzymuje ciągłą integrację oraz automatyzację.", listOf("ci", "github actions", "pipeline", "workflow")),
        AgentSpec("infrastructure-engineer", "Infrastructure Engineer", "Operacje, dokumentacja i wsparcie", "Projektuje i utrzymuje infrastrukturę uruchomieniową i wdrożeniową.", listOf("infrastrukt", "serwer", "hosting")),
        AgentSpec("observability-engineer", "Observability Engineer", "Operacje, dokumentacja i wsparcie", "Usprawnia logi, metryki, ślady i diagnostyczną widoczność.", listOf("log", "metryk", "monitor", "observability")),
        AgentSpec("sre", "SRE", "Operacje, dokumentacja i wsparcie", "Stosuje praktyki niezawodności, dostępności i obsługi incydentów.", listOf("sre", "dostępność", "niezawod")),
        AgentSpec("release-coordinator", "Release Coordinator", "Operacje, dokumentacja i wsparcie", "Koordynuje gotowość wydania, kolejność i kontrole release.", listOf("release", "wydanie", "wersja")),
        AgentSpec("migration-specialist", "Migration Specialist", "Operacje, dokumentacja i wsparcie", "Planuje i waliduje migracje danych, konfiguracji lub platform.", listOf("migrac", "aktualizac", "konwersj")),
        AgentSpec("technical-writer", "Technical Writer", "Operacje, dokumentacja i wsparcie", "Tworzy dokumentację techniczną dla programistów.", listOf("dokumentac techn", "readme", "instrukcja program")),
        AgentSpec("user-docs-writer", "User Docs Writer", "Operacje, dokumentacja i wsparcie", "Tworzy instrukcje i pomoc dla użytkowników końcowych.", listOf("instrukcja użytk", "pomoc", "poradnik")),
        AgentSpec("localization-specialist", "Localization Specialist", "Operacje, dokumentacja i wsparcie", "Dostosowuje treści produktu do języka i lokalizacji.", listOf("tłumacz", "język", "lokalizac")),
        AgentSpec("technical-editor", "Technical Editor", "Operacje, dokumentacja i wsparcie", "Poprawia jasność, spójność i poprawność tekstów technicznych.", listOf("redakc", "tekst", "spójność")),
        AgentSpec("cost-analyst", "Cost Analyst", "Operacje, dokumentacja i wsparcie", "Szacuje i ocenia koszty wdrożenia oraz eksploatacji.", listOf("koszt", "budżet", "token", "darmow", "cena")),
        AgentSpec("model-routing-specialist", "Model Routing Specialist", "Operacje, dokumentacja i wsparcie", "Dobiera modele i kieruje zadania do odpowiednich dostawców.", listOf("routing", "model", "provider", "dostawca", "fallback")),
        AgentSpec("support-analyst", "Support Analyst", "Operacje, dokumentacja i wsparcie", "Odtwarza zgłoszenia użytkowników i porządkuje dowody wsparcia.", listOf("zgłosz", "support", "problem użytk")),
        AgentSpec("incident-analyst", "Incident Analyst", "Operacje, dokumentacja i wsparcie", "Rekonstruuje incydenty, wpływ, czynniki i działania następcze.", listOf("incydent", "awaria", "przestój", "postmortem"))
    )

    fun find(id: String): AgentSpec? = all.firstOrNull { it.id == id }

    /** Full local keyword evidence. Exposes scores so learned quality can only break ties. */
    fun scoreForPrompt(prompt: String, catalog: List<AgentSpec> = all): List<AgentMatch> {
        val text = prompt.lowercase(Locale.ROOT)
        return catalog.asSequence()
            .filter { it.id != "lead-coordinator" }
            .map { spec -> AgentMatch(spec, spec.keywords.count { keyword -> text.contains(keyword.lowercase(Locale.ROOT)) }) }
            .filter { it.keywordScore > 0 }
            .sortedWith(compareByDescending<AgentMatch> { it.keywordScore }.thenBy { it.agent.id })
            .toList()
    }

    /** Simple deterministic pre-router. The coordinator remains responsible for final selection. */
    fun selectForPrompt(prompt: String, maxAgents: Int = 4, catalog: List<AgentSpec> = all): List<AgentSpec> =
        scoreForPrompt(prompt, catalog).take(maxAgents.coerceIn(1, 8)).map { it.agent }
}
