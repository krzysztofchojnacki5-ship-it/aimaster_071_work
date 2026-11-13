package pl.aimasterpro.app.core

import java.util.Locale

/** Lightweight, deterministic index. It stores paths/metadata only, never source text. */
object ProjectCodeIndex {
    data class Entry(val path: String, val sizeBytes: Long, val language: String, val module: String)
    data class Summary(
        val totalFiles: Int,
        val sourceFiles: Int,
        val totalBytes: Long,
        val languages: Map<String, Int>,
        val modules: Map<String, Int>,
        val entries: List<Entry>
    )

    private val languageByExtension = mapOf(
        "kt" to "Kotlin", "kts" to "Kotlin DSL", "java" to "Java",
        "xml" to "XML", "json" to "JSON", "md" to "Markdown", "txt" to "Text",
        "gradle" to "Gradle", "properties" to "Properties", "pro" to "ProGuard",
        "toml" to "TOML", "yaml" to "YAML", "yml" to "YAML", "html" to "HTML",
        "css" to "CSS", "js" to "JavaScript", "ts" to "TypeScript", "tsx" to "TypeScript/React",
        "jsx" to "JavaScript/React", "py" to "Python", "sh" to "Shell", "bat" to "Batch",
        "sql" to "SQL", "csv" to "CSV"
    )
    private val sourceLanguages = setOf("Kotlin", "Kotlin DSL", "Java", "JavaScript", "TypeScript", "TypeScript/React", "JavaScript/React", "Python", "SQL", "Shell", "C/C++", "Go", "Rust", "Swift", "Dart", "Ruby", "PHP")
    private val textExtensions = languageByExtension.keys + setOf("cfg", "conf", "ini", "lock", "mk", "cmake", "c", "h", "cc", "cpp", "hpp", "swift", "dart", "go", "rs", "rb", "php", "vue", "svelte")
    private val extensionlessTextFiles = setOf("readme", "license", "notice", "dockerfile", "makefile", "gradlew", "gradlew.bat", "procfile", "gemfile", "rakefile")
    private val ignoredSegments = setOf(".git", ".gradle", ".idea", "build", "captures", "node_modules", "dist", "target", "vendor", "generated")

    fun build(records: List<ProjectWorkspaceStore.FileRecord>): Summary {
        val entries = records.asSequence()
            .filter { record ->
                val segments = record.path.split('/')
                val fileName = record.path.substringAfterLast('/').lowercase(Locale.ROOT)
                val extension = fileName.substringAfterLast('.', "")
                segments.none { it.lowercase(Locale.ROOT) in ignoredSegments } &&
                    (extension in textExtensions || fileName in extensionlessTextFiles || fileName in setOf(".gitignore", ".editorconfig", ".gitattributes", ".dockerignore"))
            }
            .map { record ->
                val fileName = record.path.substringAfterLast('/')
                val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val language = languageByExtension[extension] ?: when {
                    fileName.startsWith(".") -> "Config"
                    extension in setOf("c", "h", "cc", "cpp", "hpp") -> "C/C++"
                    extension == "go" -> "Go"
                    extension == "rs" -> "Rust"
                    extension == "swift" -> "Swift"
                    extension == "dart" -> "Dart"
                    extension == "rb" -> "Ruby"
                    extension == "php" -> "PHP"
                    extensionlessTextFiles.contains(fileName) -> "Text/Script"
                    else -> "Config"
                }
                Entry(record.path, record.sizeBytes, language, moduleFor(record.path))
            }
            .sortedBy { it.path }
            .toList()
        val languages = entries.groupingBy { it.language }.eachCount().toSortedMap()
        val modules = entries.groupingBy { it.module }.eachCount().toSortedMap()
        return Summary(records.size, entries.count { it.language in sourceLanguages }, records.sumOf { it.sizeBytes }, languages, modules, entries)
    }

    /** Returns only manifest paths, ranked deterministically; no file contents are loaded. */
    fun selectRelevant(summary: Summary, query: String, maxFiles: Int = 8): List<Entry> {
        val terms = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}_-]+")).filter { it.length >= 2 }.distinct()
        return summary.entries.asSequence()
            .filter { it.language in sourceLanguages || it.language in setOf("XML", "JSON", "Gradle", "Kotlin DSL", "Text/Script") }
            .map { entry ->
                val path = entry.path.lowercase(Locale.ROOT)
                val score = terms.fold(0) { total, term ->
                    total + when {
                        path == term -> 12
                        path.substringAfterLast('/') == term -> 10
                        path.contains("/$term") -> 6
                        path.contains(term) -> 3
                        else -> 0
                    }
                }
                entry to score
            }
            .filter { terms.isEmpty() || it.second > 0 }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { it.first.path })
            .take(maxFiles.coerceIn(1, 20))
            .map { it.first }
            .toList()
    }

    private fun moduleFor(path: String): String {
        val segments = path.split('/')
        if (segments.size == 1) return "(root)"
        val first = segments.first()
        return if (first.startsWith(".") || first in setOf("gradle", "scripts", "docs")) "(root)" else first
    }
}
