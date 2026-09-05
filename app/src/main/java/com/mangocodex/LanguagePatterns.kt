package com.mangocodex

import androidx.compose.ui.graphics.Color

/**
 * Parses the patterns file into a base ("default") rule set applied to every file,
 * plus zero or more file-scoped sections that layer additional rules on top for
 * files whose *full filename* ends with one of the section's listed strings -
 * IntelliJ-style per-language customization without needing a separate rules file
 * per language, and without every language's rules having to be evaluated against
 * every file regardless of relevance.
 *
 * File format: the same `name,color,pattern` CSV rows as before, optionally split
 * into sections with a `[.py,.php]`-style header line - just a comma-separated list
 * of literal strings, no keyword. Matching is a plain case-insensitive suffix check
 * against the whole filename, not just an extension, so a section can be as specific
 * or as general as you like: `[.py]` behaves like a normal extension, but
 * `[.htaccess]`, `[.log.txt]`, or `[hosts]` all work too, matching a dotfile with no
 * extension, a compound extension, or an exact filename respectively. `[*]` is the
 * one special case - it explicitly targets the default section (only useful for
 * organizing default rules further down in the file, after a language section).
 *
 * Rows before the first header belong to the implicit default section, so an
 * existing single-section pattern file keeps working completely unchanged -
 * sections are something you opt into adding, not something required.
 *
 * A file's *effective* rule set is: that file's matching section's rules (if any),
 * followed by the default section's rules - so a language's own rules get first
 * crack at claiming a token (Lexer resolves overlaps on a first-claim basis), and the
 * defaults just fill in whatever's left, rather than the two fighting over the same
 * text or a file being forced to pick one or the other.
 */
class LanguagePatterns(csv: String) {

    private val defaultRules: List<LexerRule>
    private val sections: List<Pair<List<String>, List<LexerRule>>>

    // Built lazily per filename and cached - the same file gets tokenized on
    // essentially every keystroke while it's open, so there's no reason to
    // re-resolve and re-merge its rule list that often.
    private val lexerCache = HashMap<String, Lexer>()

    init {
        val default = mutableListOf<LexerRule>()
        val sectionList = mutableListOf<Pair<List<String>, MutableList<LexerRule>>>()
        var current: MutableList<LexerRule> = default

        for (rawLine in csv.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[") && line.endsWith("]")) {
                val inner = line.removePrefix("[").removeSuffix("]").trim()
                val suffixes = inner.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                current = if (suffixes.isEmpty() || suffixes.any { it == "*" }) {
                    default
                } else {
                    val bucket = mutableListOf<LexerRule>()
                    sectionList.add(suffixes to bucket)
                    bucket
                }
                continue
            }

            // Tolerate a header row ("name,color,pattern") at the top of the file or
            // re-pasted into any section for readability - it's not valid as a rule.
            if (line.equals("name,color,pattern", ignoreCase = true)) continue

            val parts = line.split(",", limit = 3)
            if (parts.size < 3) continue
            val name = parts[0].trim()
            val colorHex = parts[1].trim()
            val pattern = parts[2].trim()
            try {
                current.add(LexerRule(name, parseColor(colorHex), Regex(pattern)))
            } catch (e: Exception) {
                // Skip malformed rules, same as the original single-section parser.
            }
        }

        defaultRules = default
        sections = sectionList.map { it.first to it.second as List<LexerRule> }
    }

    /** The effective, cached [Lexer] for a file with the given filename (or null for an untitled/new file). */
    fun lexerFor(fileName: String?): Lexer {
        val key = fileName ?: ""
        return lexerCache.getOrPut(key) {
            val matchedSection = if (fileName != null) {
                sections.firstOrNull { (suffixes, _) -> suffixes.any { fileName.endsWith(it, ignoreCase = true) } }
            } else {
                null
            }
            val rules = if (matchedSection != null) matchedSection.second + defaultRules else defaultRules
            Lexer(rules)
        }
    }

    companion object {
        private fun parseColor(hex: String): Color {
            val clean = hex.trimStart('#')
            val value = clean.toLong(16)
            return when (clean.length) {
                6 -> Color(((0xFF shl 24) or value.toInt()))
                8 -> Color(value.toInt())
                else -> Color.White
            }
        }
    }
}
