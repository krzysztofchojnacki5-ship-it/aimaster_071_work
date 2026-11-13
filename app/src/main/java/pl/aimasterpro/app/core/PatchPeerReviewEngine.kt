package pl.aimasterpro.app.core

import java.util.Locale

/**
 * Builds an evidence-bound review package for an already validated unified diff.
 * It never calls a model, writes files, or treats an LLM verdict as authorization.
 */
object PatchPeerReviewEngine {
    const val MAX_REVIEW_DIFF_CHARS = 80_000

    enum class Severity { BLOCKER, WARNING, INFO }
    enum class ReviewState { INSUFFICIENT_REVIEWS, CONFLICTING_VERDICTS, BLOCKER_REPORTED, CHANGES_REQUESTED, REJECTED, READY_FOR_HUMAN_APPROVAL, INCONCLUSIVE }

    data class Finding(val code: String, val severity: Severity, val path: String, val message: String)
    data class ReviewPackage(
        val acceptedForReview: Boolean,
        val riskLabel: String,
        val findings: List<Finding>,
        val reviewerPrompt: String
    )
    data class ReviewerResult(val reviewerId: String, val response: String)
    data class ReviewDecision(val state: ReviewState, val parsedVerdicts: Map<String, String>, val issues: List<String>, val summary: String)

    private val criticalPath = Regex("(^|/)(AndroidManifest\\.xml|build\\.gradle(\\.kts)?|settings\\.gradle(\\.kts)?|gradle\\.properties|proguard-rules\\.pro)$", RegexOption.IGNORE_CASE)
    private val sourcePath = Regex("\\.(kt|java|xml|gradle|kts)$", RegexOption.IGNORE_CASE)
    private val testPath = Regex("(^|/)(test|androidTest)(/|$)|Test\\.(kt|java)$", RegexOption.IGNORE_CASE)
    private val verdictRegex = Regex("(?im)^\\s*VERDICT\\s*:\\s*(APPROVE|REQUEST_CHANGES|REJECT)\\s*$")

    fun prepare(preview: ProjectPatchEngine.Preview, path: String, unifiedDiff: String): ReviewPackage {
        val findings = mutableListOf<Finding>()
        val file = preview.files.singleOrNull { it.path == path }
        if (!preview.accepted || file == null || unifiedDiff.isBlank()) {
            findings += Finding("REVIEW_INPUT_INVALID", Severity.BLOCKER, path.take(240), "Recenzja wymaga zaakceptowanego preview i dokładnego diffu odpowiadającego jednemu plikowi.")
            return ReviewPackage(false, "BLOCKED", findings, "")
        }

        if (unifiedDiff.length > MAX_REVIEW_DIFF_CHARS) {
            findings += Finding("REVIEW_DIFF_TOO_LARGE", Severity.BLOCKER, path, "Diff przekracza limit ${MAX_REVIEW_DIFF_CHARS / 1024} KB dla kompletnej recenzji; podziel zmianę na mniejsze patche.")
            return ReviewPackage(false, "BLOCKED", findings, "")
        }
        val normalizedPath = path.replace('\\', '/')
        val diffLines = unifiedDiff.lineSequence().toList()
        val added = diffLines.filter { it.startsWith("+") && !it.startsWith("+++") }.map { it.drop(1) }
        val removed = diffLines.filter { it.startsWith("-") && !it.startsWith("---") }.map { it.drop(1) }
        val changedText = (added + removed).joinToString("\n")
        val lower = changedText.lowercase(Locale.ROOT)

        if (file.operation == "DELETE" && criticalPath.containsMatchIn(normalizedPath)) {
            findings += Finding("DELETE_CRITICAL_FILE", Severity.BLOCKER, path, "Patch usuwa krytyczny plik konfiguracji lub manifestu.")
        }
        if (criticalPath.containsMatchIn(normalizedPath) && file.operation != "DELETE") {
            findings += Finding("MODIFY_CRITICAL_FILE", Severity.WARNING, path, "Zmiana pliku konfiguracji/build/manifestu wymaga szczególnej kontroli skutków ubocznych.")
        }
        val addedText = added.joinToString("\n")
        if (Regex("(api[_-]?key|api[_-]?token|client[_-]?secret|password|passwd)\\s*[:=]\\s*[\\\"'][^\\\"']{8,}[\\\"']", RegexOption.IGNORE_CASE).containsMatchIn(addedText)) {
            findings += Finding("POSSIBLE_CREDENTIAL_LITERAL", Severity.BLOCKER, path, "W zmienionych liniach wykryto możliwą wartość poświadczenia; sprawdź, czy nie jest to prawdziwy sekret.")
        }
        val riskyPatterns = listOf(
            "TLS_CERT_VALIDATION_DISABLED" to Regex("(trustall|hostnameverifier\\s*\\{\\s*_,\\s*_\\s*->\\s*true|checkservertrusted\\s*\\([^)]*\\)\\s*\\{\\s*\\})", RegexOption.IGNORE_CASE),
            "DYNAMIC_CODE_EXECUTION" to Regex("(runtime\\.getruntime\\(\\)\\.exec|processbuilder\\s*\\(|dexclassloader|pathclassloader)", RegexOption.IGNORE_CASE),
            "WEBVIEW_JS_BRIDGE" to Regex("(addjavascriptinterface|setjavascriptenabled\\s*=\\s*true)", RegexOption.IGNORE_CASE),
            "SECRET_LOGGING" to Regex("(log[vdiew]\\s*\\([^\\n]*(api[_-]?key|token|password|secret)|println\\s*\\([^\\n]*(api[_-]?key|token|password|secret))", RegexOption.IGNORE_CASE),
            "CLEAR_TEXT_TRAFFIC" to Regex("usesCleartextTraffic\\s*=\\s*\\\"true\\\"", RegexOption.IGNORE_CASE),
            "BROAD_EXCEPTION_SWALLOW" to Regex("catch\\s*\\([^)]*Throwable[^)]*\\)\\s*\\{\\s*\\}", RegexOption.IGNORE_CASE)
        )
        riskyPatterns.forEach { (code, regex) ->
            if (regex.containsMatchIn(changedText)) findings += Finding(code, Severity.WARNING, path, "Zmiana zawiera wzorzec wymagający ręcznej oceny: $code.")
        }
        if (lower.contains("android.permission") || lower.contains("uses-permission") || lower.contains("exported=\"true\"")) {
            findings += Finding("ANDROID_PERMISSION_OR_EXPORTED_COMPONENT", Severity.WARNING, path, "Zmieniają się uprawnienia Androida lub ekspozycja komponentu.")
        }
        if (lower.contains("implementation(") || lower.contains("api(") || lower.contains("classpath(") || lower.contains("plugins {") || lower.contains("versioncatalog")) {
            findings += Finding("BUILD_DEPENDENCY_CHANGE", Severity.WARNING, path, "Zmiana może modyfikować pluginy, zależności lub konfigurację builda.")
        }
        if (file.operation == "DELETE" && sourcePath.containsMatchIn(path)) {
            findings += Finding("SOURCE_FILE_DELETION", Severity.WARNING, path, "Usuwany jest plik źródłowy; sprawdź wszystkie referencje i konfigurację projektu.")
        }
        if (sourcePath.containsMatchIn(path) && !testPath.containsMatchIn(path) && added.size >= 8) {
            findings += Finding("TEST_COVERAGE_REVIEW", Severity.WARNING, path, "Zmiana obejmuje co najmniej 8 dodanych linii kodu produkcyjnego; recenzent powinien wskazać wymagane testy regresji.")
        }
        if (added.size + removed.size > 250) {
            findings += Finding("LARGE_PATCH", Severity.WARNING, path, "Duża liczba zmienionych linii utrudnia niezależną weryfikację; rozważ podział patcha.")
        }
        findings += Finding("HUMAN_APPROVAL_REQUIRED", Severity.INFO, path, "Nawet pozytywna recenzja nie zatwierdza ani nie stosuje patcha.")

        val blockers = findings.count { it.severity == Severity.BLOCKER }
        val warnings = findings.count { it.severity == Severity.WARNING }
        val risk = when { blockers > 0 -> "BLOCKED"; warnings >= 3 -> "HIGH"; warnings > 0 -> "ELEVATED"; else -> "LOW" }
        val prompt = buildReviewerPrompt(file, unifiedDiff, findings)
        return ReviewPackage(blockers == 0, risk, findings, prompt)
    }

    fun combine(
        reviewPackage: ReviewPackage,
        reviewerResults: List<ReviewerResult>,
        minimumIndependentReviewers: Int = 2
    ): ReviewDecision {
        require(minimumIndependentReviewers >= 1)
        if (!reviewPackage.acceptedForReview) return ReviewDecision(ReviewState.REJECTED, emptyMap(), emptyList(), "Recenzja zablokowana przez lokalne kontrole bezpieczeństwa.")
        val unique = reviewerResults.filter { it.reviewerId.isNotBlank() }.distinctBy { it.reviewerId }
        val parsed = unique.mapNotNull { result ->
            verdictRegex.find(result.response)?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT)?.let { result.reviewerId to it }
        }.toMap()
        val issues = unique.flatMap { result ->
            Regex("(?im)^\\s*(?:ISSUE|PROBLEM)\\s*:\\s*(.+)$").findAll(result.response).map { "${result.reviewerId}: ${it.groupValues[1].trim().take(400)}" }.toList()
        }.distinct().take(30)
        val verdicts = parsed.values.toSet()
        val reviewerReportedBlocker = issues.any { Regex("\\[(BLOCKER|CRITICAL)]", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        // A single explicit negative finding must not be hidden by the minimum-review gate.
        // Only positive approval requires the full quorum; disagreement remains a conflict.
        val state = when {
            verdicts.size > 1 -> ReviewState.CONFLICTING_VERDICTS
            verdicts == setOf("REJECT") -> ReviewState.REJECTED
            verdicts == setOf("REQUEST_CHANGES") -> ReviewState.CHANGES_REQUESTED
            reviewerReportedBlocker -> ReviewState.BLOCKER_REPORTED
            parsed.size < minimumIndependentReviewers -> ReviewState.INSUFFICIENT_REVIEWS
            verdicts == setOf("APPROVE") -> ReviewState.READY_FOR_HUMAN_APPROVAL
            else -> ReviewState.INCONCLUSIVE
        }
        val summary = when (state) {
            ReviewState.CONFLICTING_VERDICTS -> "Recenzenci nie są zgodni. Master musi przedstawić rozbieżności; nie wolno uśredniać werdyktów."
            ReviewState.BLOCKER_REPORTED -> "Co najmniej jeden recenzent zgłosił BLOCKER. Patch pozostaje zablokowany do wyjaśnienia i ponownej recenzji."
            ReviewState.REJECTED -> "Co najmniej recenzent odrzucił patch. Nie wolno go stosować."
            ReviewState.CHANGES_REQUESTED -> "Recenzenci żądają poprawek. Wymagana nowa wersja diffu i ponowna recenzja."
            ReviewState.READY_FOR_HUMAN_APPROVAL -> "Recenzje są pozytywne. Patch nadal wymaga jawnej zgody użytkownika i osobnego etapu bezpiecznego zapisu."
            ReviewState.INSUFFICIENT_REVIEWS -> "Niewystarczająca liczba niezależnych recenzji."
            ReviewState.INCONCLUSIVE -> "Nie udało się jednoznacznie ustalić werdyktu."
        }
        return ReviewDecision(state, parsed, issues, summary)
    }

    private fun buildReviewerPrompt(file: ProjectPatchEngine.FilePreview, diff: String, findings: List<Finding>): String = buildString {
        appendLine("NIEZALEŻNY PEER REVIEW — RZECZYWISTY UNIFIED DIFF")
        appendLine("Jesteś niezależnym recenzentem. Nie zakładaj, że autor patcha lub MASTER ma rację.")
        appendLine("Plik: ${file.path}")
        appendLine("Operacja: ${file.operation}")
        appendLine("Bazowy SHA-256: ${file.baseSha256.ifBlank { "(nowy plik)" }}")
        appendLine("Wynikowy SHA-256: ${file.resultSha256 ?: "(plik usuwany)"}")
        appendLine("Liczba linii: ${file.oldLines} -> ${file.newLines}")
        appendLine("Lokalne sygnały ryzyka: ${findings.filter { it.severity != Severity.INFO }.joinToString("; ") { "${it.code} (${it.severity})" }.ifBlank { "brak" }}")
        appendLine()
        appendLine("Sprawdź: poprawność logiki, regresje, bezpieczeństwo, uprawnienia, sekrety, zgodność Android/Gradle, obsługę błędów, współbieżność, kompatybilność i testy.")
        appendLine("Każdy problem musi mieć dowód: ścieżkę, numer linii z diffu lub jednoznaczny fragment. Nie wymyślaj wyników kompilacji ani testów.")
        appendLine("Nie zatwierdzaj zmian na podstawie samego opisu. Jeśli brakuje kontekstu, wskaż czego brakuje.")
        appendLine("Zwróć dokładnie jeden werdykt w formacie: VERDICT: APPROVE albo VERDICT: REQUEST_CHANGES albo VERDICT: REJECT.")
        appendLine("Dla każdego problemu dodaj osobną linię ISSUE: [BLOCKER|WARNING|INFO] opis + dowód + proponowana poprawka.")
        appendLine("Na końcu podaj: MISSING_EVIDENCE: ... oraz REQUIRED_TESTS: ...")
        appendLine("\n--- BEGIN UNIFIED DIFF ---")
        append(diff)
        appendLine("\n--- END UNIFIED DIFF ---")
    }
}
