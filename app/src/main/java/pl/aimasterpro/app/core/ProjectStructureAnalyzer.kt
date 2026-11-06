package pl.aimasterpro.app.core

/** Bounded, local-only structural scan for Kotlin and Java source files. */
object ProjectStructureAnalyzer {
    const val MAX_FILES = 300
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L

    data class Symbol(
        val name: String,
        val kind: String,
        val qualifiedName: String,
        val line: Int
    )

    data class FileAnalysis(
        val path: String,
        val language: String,
        val packageName: String,
        val imports: List<String>,
        val symbols: List<Symbol>,
        val bytesRead: Int
    )

    data class DependencyEdge(val from: String, val to: String, val imports: List<String>)

    data class Report(
        val analyzedFiles: List<FileAnalysis>,
        val skippedFiles: List<Pair<String, String>>,
        val edges: List<DependencyEdge>,
        val unresolvedImports: Map<String, List<String>>,
        val duplicateSymbols: Map<String, List<String>>,
        val moduleEdges: Map<String, Set<String>>,
        val totalBytesRead: Long
    ) {
        val symbolCount: Int get() = analyzedFiles.sumOf { it.symbols.size }
        val importCount: Int get() = analyzedFiles.sumOf { it.imports.size }
    }

    /** Reader must enforce manifest ownership, SHA-256 and secret filtering. */
    fun analyze(
        records: List<ProjectWorkspaceStore.FileRecord>,
        reader: (String, Int) -> ProjectWorkspaceStore.TextFile
    ): Report {
        val candidates = records.asSequence()
            .filter { it.path.endsWith(".kt", true) || it.path.endsWith(".kts", true) || it.path.endsWith(".java", true) }
            .sortedBy { it.path }
            .toList()
        val analyses = mutableListOf<FileAnalysis>()
        val skipped = mutableListOf<Pair<String, String>>()
        var totalBytes = 0L

        candidates.forEach { record ->
            if (analyses.size >= MAX_FILES) {
                skipped += record.path to "Przekroczono limit $MAX_FILES plików analizy."
                return@forEach
            }
            if (record.sizeBytes > MAX_FILE_BYTES) {
                skipped += record.path to "Plik przekracza limit 64 KB analizy."
                return@forEach
            }
            if (totalBytes + record.sizeBytes > MAX_TOTAL_BYTES) {
                skipped += record.path to "Przekroczono łączny limit 4 MB analizy."
                return@forEach
            }
            val text = runCatching { reader(record.path, MAX_FILE_BYTES) }
            if (text.isFailure) {
                skipped += record.path to (text.exceptionOrNull()?.message ?: "Odczyt zablokowany.")
                return@forEach
            }
            val content = text.getOrThrow().content
            val actualBytes = content.toByteArray(Charsets.UTF_8).size
            if (actualBytes > MAX_FILE_BYTES) {
                skipped += record.path to "Plik przekracza limit po dekodowaniu."
                return@forEach
            }
            totalBytes += actualBytes
            analyses += parseFile(record.path, content, actualBytes)
        }

        val byQualifiedName = mutableMapOf<String, MutableList<String>>()
        val byPackage = analyses.groupBy { it.packageName }
        analyses.forEach { file ->
            file.symbols.forEach { symbol ->
                byQualifiedName.getOrPut(symbol.qualifiedName) { mutableListOf() }.add(file.path)
            }
        }
        val edges = linkedMapOf<Pair<String, String>, MutableSet<String>>()
        val unresolved = linkedMapOf<String, List<String>>()
        analyses.forEach { file ->
            val unresolvedForFile = mutableListOf<String>()
            file.imports.forEach { imported ->
                val normalized = imported.removePrefix("static ").removeSuffix(".*")
                val exact = byQualifiedName[normalized].orEmpty()
                val ownerName = normalized.substringBeforeLast('.', "")
                val ownerTargets = byQualifiedName[ownerName].orEmpty()
                val wildcardTargets = if (imported.endsWith(".*")) {
                    byQualifiedName[normalized].orEmpty() + byPackage[normalized].orEmpty().map { it.path }
                } else emptyList()
                val targets = (exact + ownerTargets + wildcardTargets).distinct().filter { it != file.path }
                if (targets.isEmpty() && !imported.endsWith(".*")) unresolvedForFile += imported
                targets.forEach { target ->
                    edges.getOrPut(file.path to target) { linkedSetOf() }.add(imported)
                }
            }
            if (unresolvedForFile.isNotEmpty()) unresolved[file.path] = unresolvedForFile.distinct().sorted()
        }
        // Class-like declarations are safe duplicate candidates; overloaded functions are legal.
        val classQualifiedNames = analyses.flatMap { file ->
            file.symbols.filter { it.kind in setOf("class", "interface", "object", "record", "enum") }
                .map { it.qualifiedName to file.path }
        }.groupBy({ it.first }, { it.second })
        val duplicateSymbols = classQualifiedNames.filterValues { it.distinct().size > 1 }
            .mapValues { it.value.distinct().sorted() }.toSortedMap()
        val moduleEdges = linkedMapOf<String, MutableSet<String>>()
        val pathModule = analyses.associate { it.path to moduleFor(it.path) }
        edges.keys.forEach { (from, to) ->
            val sourceModule = pathModule[from] ?: "(root)"
            val targetModule = pathModule[to] ?: "(root)"
            if (sourceModule != targetModule) moduleEdges.getOrPut(sourceModule) { sortedSetOf() }.add(targetModule)
        }
        return Report(
            analyzedFiles = analyses,
            skippedFiles = skipped,
            edges = edges.map { (pair, imports) -> DependencyEdge(pair.first, pair.second, imports.sorted()) }
                .sortedWith(compareBy<DependencyEdge> { it.from }.thenBy { it.to }),
            unresolvedImports = unresolved,
            duplicateSymbols = duplicateSymbols,
            moduleEdges = moduleEdges.mapValues { it.value.toSortedSet() }.toSortedMap(),
            totalBytesRead = totalBytes
        )
    }

    private fun parseFile(path: String, source: String, bytes: Int): FileAnalysis {
        val masked = maskCommentsAndStrings(source)
        val lineStarts = mutableListOf(0)
        source.forEachIndexed { index, char -> if (char == '\n') lineStarts += index + 1 }
        val packageName = PACKAGE.find(masked)?.groupValues?.get(1).orEmpty()
        val imports = IMPORT.findAll(masked).map { it.groupValues[1] }.distinct().sorted().toList()
        val symbols = mutableListOf<Symbol>()
        DECLARATION.findAll(masked).forEach { match ->
            val kind = if (match.groupValues[0].contains(Regex("\\benum\\s+class\\b"))) "enum" else match.groupValues[1]
            val name = match.groupValues[2]
            symbols += Symbol(name, kind, listOf(packageName, name).filter { it.isNotBlank() }.joinToString("."), lineAt(lineStarts, match.range.first))
        }
        FUNCTION.findAll(masked).forEach { match ->
            val name = match.groupValues[1]
            if (name !in CONTROL_WORDS) {
                symbols += Symbol(name, "function", listOf(packageName, name).filter { it.isNotBlank() }.joinToString("."), lineAt(lineStarts, match.range.first))
            }
        }
        if (path.endsWith(".java", true)) {
            JAVA_ANNOTATION.findAll(masked).forEach { match ->
                val name = match.groupValues[1]
                symbols += Symbol(name, "interface", listOf(packageName, name).filter { it.isNotBlank() }.joinToString("."), lineAt(lineStarts, match.range.first))
            }
            val typeNames = symbols.filter { it.kind in setOf("class", "interface", "record", "enum") }.map { it.name }.toSet()
            JAVA_METHOD.findAll(masked).forEach { match ->
                val name = match.groupValues[1]
                if (name !in CONTROL_WORDS && name !in typeNames) {
                    symbols += Symbol(name, "method", listOf(packageName, name).filter { it.isNotBlank() }.joinToString("."), lineAt(lineStarts, match.range.first))
                }
            }
        }
        val unique = symbols.distinctBy { Triple(it.kind, it.name, it.line) }.sortedWith(compareBy<Symbol> { it.line }.thenBy { it.kind }.thenBy { it.name })
        return FileAnalysis(path, if (path.endsWith(".java", true)) "Java" else "Kotlin", packageName, imports, unique, bytes)
    }

    /** Replaces comments and string/character literal contents while preserving offsets and line breaks. */
    private fun maskCommentsAndStrings(source: String): String {
        val out = source.toCharArray()
        var i = 0
        var blockDepth = 0
        var state = 0 // 0 code, 1 line comment, 2 block comment, 3 quoted string, 4 char, 5 raw string/text block
        while (i < source.length) {
            val c = source[i]
            val n = source.getOrNull(i + 1)
            when (state) {
                0 -> when {
                    c == '/' && n == '/' -> { out[i] = ' '; out[i + 1] = ' '; i += 2; state = 1; continue }
                    c == '/' && n == '*' -> { out[i] = ' '; out[i + 1] = ' '; i += 2; state = 2; blockDepth = 1; continue }
                    source.startsWith("\"\"\"", i) -> { repeat(3) { out[i + it] = ' ' }; i += 3; state = 5; continue }
                    c == '"' -> { out[i] = ' '; i++; state = 3; continue }
                    c == '\'' -> { out[i] = ' '; i++; state = 4; continue }
                }
                1 -> if (c == '\n') state = 0 else out[i] = ' '
                2 -> when {
                    c == '/' && n == '*' -> { out[i] = ' '; out[i + 1] = ' '; i += 2; blockDepth++; continue }
                    c == '*' && n == '/' -> { out[i] = ' '; out[i + 1] = ' '; i += 2; blockDepth--; if (blockDepth == 0) state = 0; continue }
                    c != '\n' && c != '\r' -> out[i] = ' '
                }
                3, 4 -> when {
                    c == '\\' -> { out[i] = ' '; if (i + 1 < source.length) out[i + 1] = ' '; i += 2; continue }
                    (state == 3 && c == '"') || (state == 4 && c == '\'') -> { out[i] = ' '; state = 0 }
                    c == '\n' && state == 4 -> state = 0
                    c != '\n' && c != '\r' -> out[i] = ' '
                }
                5 -> when {
                    source.startsWith("\"\"\"", i) -> { repeat(3) { out[i + it] = ' ' }; i += 3; state = 0; continue }
                    c != '\n' && c != '\r' -> out[i] = ' '
                }
            }
            i++
        }
        return String(out)
    }


    private fun lineAt(lineStarts: List<Int>, offset: Int): Int {
        val result = lineStarts.binarySearch(offset)
        return (if (result >= 0) result else -result - 2) + 1
    }

    private fun moduleFor(path: String): String = path.substringBefore('/', "(root)").let { if (it.startsWith('.')) "(root)" else it }

    private val PACKAGE = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)")
    private val IMPORT = Regex("(?m)^\\s*import\\s+(?:static\\s+)?([A-Za-z_][\\w.$*]*)(?:\\s+as\\s+[A-Za-z_][\\w]*)?\\s*;?")
    private val DECLARATION = Regex("\\b(?:(?:public|private|protected|internal|abstract|open|final|sealed|data|value|inner|static|strictfp|annotation|enum)\\s+)*(class|interface|object|record|enum)\\s+([A-Za-z_$][\\w$]*)")
    private val FUNCTION = Regex("\\bfun\\s+(?:<[^>]+>\\s*)?(?:[A-Za-z_][\\w?.<>]*\\.)?([A-Za-z_][\\w]*)\\s*\\(")
    private val JAVA_ANNOTATION = Regex("\\b@interface\\s+([A-Za-z_$][\\w$]*)")
    private val JAVA_METHOD = Regex("(?m)^[\\t ]*(?:(?:public|private|protected|static|final|abstract|synchronized|native|strictfp|default)\\s+)*(?:<[^>]+>\\s*)?(?:[A-Za-z_$][\\w$?.<>\\[\\],]*\\s+)+([A-Za-z_$][\\w$]*)\\s*\\(")
    private val CONTROL_WORDS = setOf("if", "for", "while", "when", "catch", "switch", "synchronized", "return", "throw", "new", "assert", "this", "super", "else")
}
