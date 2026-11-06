package pl.aimasterpro.app.core

/**
 * Extracts the terms a user is likely to copy from a Master answer: inline `code` spans (model IDs,
 * commands, names) and http(s) links. Pure logic; the chat UI turns each result into its own copy
 * control. Results keep their order of appearance and are de-duplicated.
 */
object CopyableTerms {
    private val inlineCode = Regex("`([^`\\n]{1,200})`")
    private val link = Regex("https?://[^\\s<>\"'`]+")
    private const val TRAILING_PUNCTUATION = ".,;:!?)"

    fun extract(text: String): List<String> {
        val hits = mutableListOf<Pair<Int, String>>()
        inlineCode.findAll(text).forEach { hits.add(it.range.first to it.groupValues[1].trim()) }
        link.findAll(text).forEach { hits.add(it.range.first to it.value.trimEnd { c -> c in TRAILING_PUNCTUATION }) }
        return hits.sortedBy { it.first }.map { it.second }.filter { it.isNotBlank() }.distinct()
    }
}
