package pl.aimasterpro.app.core

import java.security.MessageDigest
import java.util.Locale

/**
 * Validates and previews a deliberately limited unified-diff format.
 * This engine NEVER writes to the workspace or executes project code.
 */
object ProjectPatchEngine {
    const val MAX_PATCHES = 20
    const val MAX_PATCH_CHARS = 256_000
    const val MAX_RESULT_CHARS = 512_000
    const val MAX_HUNKS = 200
    const val MAX_LINE_CHARS = 16_384

    data class Proposal(
        val path: String,
        /** SHA-256 of the manifest version; empty only for a new file. */
        val baseSha256: String,
        val unifiedDiff: String
    )

    data class FilePreview(
        val path: String,
        val operation: String,
        val baseSha256: String,
        val resultSha256: String?,
        val oldLines: Int,
        val newLines: Int,
        val resultContent: String?
    )

    data class Finding(val code: String, val message: String, val blocking: Boolean = true)
    data class Preview(val accepted: Boolean, val files: List<FilePreview>, val findings: List<Finding>)

    private data class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<Pair<Char, String>>)
    private data class Parsed(val oldPath: String?, val newPath: String?, val hunks: List<Hunk>, val resultNoFinalNewline: Boolean)

    fun preview(
        proposals: List<Proposal>,
        manifestFiles: List<ProjectWorkspaceStore.FileRecord>,
        reader: (String, Int) -> ProjectWorkspaceStore.TextFile
    ): Preview {
        val findings = mutableListOf<Finding>()
        val previews = mutableListOf<FilePreview>()
        if (proposals.isEmpty()) findings += Finding("EMPTY_PATCH", "Brak poprawek do sprawdzenia.")
        if (proposals.size > MAX_PATCHES) findings += Finding("TOO_MANY_PATCHES", "Limit wynosi $MAX_PATCHES plików w jednym podglądzie.")
        val manifest = manifestFiles.associateBy { it.path }
        val seen = mutableSetOf<String>()
        var totalChars = 0L

        proposals.take(MAX_PATCHES).forEachIndexed { index, proposal ->
            try {
                require(proposal.unifiedDiff.isNotBlank()) { "Pusty diff." }
                totalChars += proposal.unifiedDiff.length
                require(proposal.unifiedDiff.length <= MAX_PATCH_CHARS) { "Diff przekracza limit 256 KB." }
                require(totalChars <= MAX_RESULT_CHARS) { "Łączny rozmiar propozycji przekracza limit." }
                val parsed = parse(proposal.unifiedDiff)
                val path = normalizePath(proposal.path)
                require(path == proposal.path) { "Ścieżka musi być względna i znormalizowana." }
                require(seen.add(path.lowercase(Locale.ROOT))) { "Ten sam plik występuje w diffie więcej niż raz." }
                val oldPath = parsed.oldPath
                val newPath = parsed.newPath
                require(oldPath == null || normalizePath(oldPath) == path) { "Ścieżka --- nie zgadza się z deklarowaną ścieżką." }
                require(newPath == null || normalizePath(newPath) == path) { "Ścieżka +++ nie zgadza się z deklarowaną ścieżką." }
                require(oldPath != null || newPath != null) { "Nie można jednocześnie usunąć i dodać /dev/null." }
                val deleting = newPath == null
                val adding = oldPath == null
                val record = manifest[path]
                if (adding) {
                    require(record == null) { "Plik już istnieje w manifeście; dodawanie nie może go nadpisać." }
                    require(proposal.baseSha256.isBlank()) { "Nowy plik nie może mieć bazowego SHA-256." }
                } else {
                    require(record != null) { "Plik źródłowy nie należy do manifestu workspace." }
                    require(proposal.baseSha256.matches(Regex("[0-9a-f]{64}"))) { "Brak poprawnego bazowego SHA-256." }
                    require(record.sha256 == proposal.baseSha256) { "Konflikt wersji: bazowy SHA-256 różni się od manifestu." }
                }
                val oldContent = if (adding) "" else reader(path, 64 * 1024).also {
                    require(it.sha256 == proposal.baseSha256) { "Konflikt wersji: zawartość zmieniła się od przygotowania patcha." }
                }.content
                val newContent = applyHunks(oldContent, parsed.hunks, adding, parsed.resultNoFinalNewline)
                require(newContent.length <= MAX_RESULT_CHARS) { "Wynikowy plik przekracza limit." }
                val operation = when { adding -> "ADD"; deleting -> "DELETE"; else -> "MODIFY" }
                if (deleting) require(newContent.isEmpty()) { "Patch usuwający musi pozostawić pustą zawartość." }
                val validationContent = if (deleting) emptyMap() else mapOf(path to newContent)
                val safety = ChangeSafetyGuard.validate(listOf(path), validationContent, maxFiles = 1, maxTotalChars = MAX_RESULT_CHARS)
                require(safety.allowed) { safety.findings.joinToString("; ") { it.message } }
                val result = if (deleting) null else newContent
                previews += FilePreview(path, operation, proposal.baseSha256, result?.let(::sha256),
                    lineCount(oldContent), lineCount(newContent), result)
            } catch (t: Throwable) {
                findings += Finding("PATCH_${index + 1}_REJECTED", "${proposal.path.take(160)}: ${(t.message ?: "Nieznany błąd walidacji").take(500)}")
            }
        }
        return Preview(findings.none { it.blocking } && previews.size == proposals.size, previews, findings)
    }

    private fun parse(diff: String): Parsed {
        require(!diff.contains('\u0000')) { "Diff zawiera bajt NUL." }
        require(!diff.contains('\r')) { "Diff musi używać zakończeń linii LF." }
        val lines = diff.split('\n')
        require(lines.size >= 4) { "Niepełny unified diff." }
        require(lines[0].startsWith("--- ") && lines[1].startsWith("+++ ")) { "Brak nagłówków --- / +++." }
        val oldPath = parseHeaderPath(lines[0].removePrefix("--- "))
        val newPath = parseHeaderPath(lines[1].removePrefix("+++ "))
        require(oldPath != null || newPath != null) { "Diff nie może być pusty dla obu stron." }
        val hunks = mutableListOf<Hunk>()
        var resultNoFinalNewline = false
        var i = 2
        while (i < lines.size) {
            val header = lines[i]
            if (header.isEmpty() && i == lines.lastIndex) break
            val match = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?:.*)$").matchEntire(header)
                ?: error("Nieprawidłowy nagłówek hunk w linii ${i + 1}.")
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues[2].ifEmpty { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues[4].ifEmpty { "1" }.toInt()
            require(oldStart >= 0 && newStart >= 0 && oldCount >= 0 && newCount >= 0) { "Ujemny zakres hunk." }
            i++
            val body = mutableListOf<Pair<Char, String>>()
            var oldSeen = 0
            var newSeen = 0
            while (i < lines.size && !lines[i].startsWith("@@ ")) {
                val line = lines[i]
                if (line.isEmpty() && i == lines.lastIndex) break
                if (line == "\\ No newline at end of file") {
                    if (body.lastOrNull()?.first in setOf(' ', '+')) resultNoFinalNewline = true
                    i++
                    continue
                }
                require(line.isNotEmpty() && line[0] in charArrayOf(' ', '+', '-')) { "Nieprawidłowa linia hunk w linii ${i + 1}." }
                require(line.length <= MAX_LINE_CHARS + 1) { "Linia diffu jest zbyt długa." }
                val kind = line[0]
                val value = line.substring(1)
                if (kind == ' ' || kind == '+') resultNoFinalNewline = false
                body += kind to value
                if (kind != '+') oldSeen++
                if (kind != '-') newSeen++
                i++
            }
            require(oldSeen == oldCount && newSeen == newCount) { "Liczba linii hunk nie zgadza się z nagłówkiem (oczekiwano -$oldCount +$newCount, jest -$oldSeen +$newSeen)." }
            hunks += Hunk(oldStart, oldCount, newStart, newCount, body)
            require(hunks.size <= MAX_HUNKS) { "Przekroczono limit $MAX_HUNKS hunków." }
        }
        require(hunks.isNotEmpty()) { "Diff nie zawiera żadnych hunków." }
        return Parsed(oldPath, newPath, hunks, resultNoFinalNewline)
    }

    private fun parseHeaderPath(value: String): String? {
        val path = value.substringBefore('\t').trim()
        if (path == "/dev/null") return null
        require(path.startsWith("a/") || path.startsWith("b/")) { "Nagłówek diff musi używać prefiksu a/ lub b/." }
        return normalizePath(path.substring(2))
    }

    private fun normalizePath(path: String): String {
        require(path.isNotBlank() && path.length <= 240 && !path.startsWith('/') && !path.contains('\\') && !path.contains('\u0000')) { "Niebezpieczna ścieżka." }
        val parts = path.split('/')
        require(parts.size <= 30 && parts.none { it.isBlank() || it == "." || it == ".." || ':' in it || it.any { c -> c.code < 32 } }) { "Niebezpieczna ścieżka." }
        return parts.joinToString("/")
    }

    private fun applyHunks(original: String, hunks: List<Hunk>, adding: Boolean, noFinalNewline: Boolean): String {
        val hadFinalNewline = original.endsWith('\n')
        val source = if (original.isEmpty()) mutableListOf() else original.removeSuffix("\n").split('\n').toMutableList()
        val output = mutableListOf<String>()
        var cursor = 0
        var previousOldEnd = 0
        var previousWasInsertion = false
        hunks.forEach { hunk ->
            val start = if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1
            require(start >= cursor && start >= previousOldEnd && start <= source.size) { "Hunk nachłożony, cofający się lub poza zakresem pliku." }
            require(!(previousWasInsertion && hunk.oldCount == 0 && start == previousOldEnd)) { "Dwa hunki wstawiające w to samo miejsce są niejednoznaczne." }
            val expectedNewStart = (hunk.newStart - 1).coerceAtLeast(0)
            require(output.size + (start - cursor) == expectedNewStart) { "Pozycja +newStart hunk nie zgadza się z wynikiem poprzednich zmian." }
            while (cursor < start) output += source[cursor++]
            hunk.lines.forEach { (kind, value) ->
                when (kind) {
                    ' ' -> { require(cursor < source.size && source[cursor] == value) { "Kontekst hunk nie pasuje dokładnie do pliku bazowego." }; output += value; cursor++ }
                    '-' -> { require(cursor < source.size && source[cursor] == value) { "Usuwana linia nie pasuje dokładnie do pliku bazowego." }; cursor++ }
                    '+' -> output += value
                }
            }
            previousOldEnd = start + hunk.oldCount
            previousWasInsertion = hunk.oldCount == 0
        }
        while (cursor < source.size) output += source[cursor++]
        if (adding) require(original.isEmpty()) { "Nowy plik musi zaczynać się od pustej zawartości." }
        val content = output.joinToString("\n")
        val resultHasFinalNewline = !noFinalNewline && (adding || hadFinalNewline)
        return if (resultHasFinalNewline && content.isNotEmpty()) "$content\n" else content
    }

    private fun lineCount(content: String): Int = if (content.isEmpty()) 0 else content.count { it == '\n' } + if (content.endsWith('\n')) 0 else 1
    private fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
