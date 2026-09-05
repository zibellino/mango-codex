package com.mangocodex

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val PATTERNS_INTERNAL_PATH = "patterns.csv"

private const val WINDOW_MARGIN_LINES = 150
private const val WINDOW_SAFETY_LINES = WINDOW_MARGIN_LINES / 3
private const val CACHE_RETENTION_LINES = WINDOW_MARGIN_LINES * 3

data class HighlightSpan(val start: Int, val end: Int, val color: Color)

/**
 * Unlike the previous Compose-BasicTextField version, this ViewModel does not own
 * cursor/selection state - the native EditText (see HighlightingEditText /
 * CodeEditorView) is the source of truth for that, since Android's own widget
 * already handles it efficiently (DynamicLayout incremental reflow, small dirty-rect
 * invalidation for blink/selection) in a way BasicTextField's Paragraph-based layout
 * does not. This class only tracks document content (for save/dirty) and drives the
 * same windowed-highlighting strategy as before, now expressed as spans to apply
 * directly onto the EditText's Editable instead of rebuilding an AnnotatedString.
 */
class EditorViewModel : ViewModel() {

    private var languagePatterns: LanguagePatterns = LanguagePatterns("")
    // Re-resolved (and tokenCache invalidated) only when the current file's name
    // actually changes or patterns get reloaded - not on every computeSpans() call,
    // which happens on essentially every keystroke. "\u0000" is a sentinel that can
    // never equal a real filename (or null), forcing the first real resolution.
    private var cachedLexerFileName: String? = "\u0000"
    private var lexer: Lexer = Lexer(emptyList())

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    private val _currentFileUri = MutableStateFlow<Uri?>(null)
    val currentFileUri: StateFlow<Uri?> = _currentFileUri

    private val _currentFileName = MutableStateFlow<String?>(null)
    val currentFileName: StateFlow<String?> = _currentFileName

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty

    private val _wrapLines = MutableStateFlow(true)
    val wrapLines: StateFlow<Boolean> = _wrapLines

    private val _showLineNumbers = MutableStateFlow(true)
    val showLineNumbers: StateFlow<Boolean> = _showLineNumbers

    private val _autoIndent = MutableStateFlow(true)
    val autoIndent: StateFlow<Boolean> = _autoIndent

    private val _findBarVisible = MutableStateFlow(false)
    val findBarVisible: StateFlow<Boolean> = _findBarVisible

    private val _findQuery = MutableStateFlow("")
    val findQuery: StateFlow<String> = _findQuery

    private val _replaceQuery = MutableStateFlow("")
    val replaceQuery: StateFlow<String> = _replaceQuery

    private val _isPatternFile = MutableStateFlow(false)
    val isPatternFile: StateFlow<Boolean> = _isPatternFile

    // Bumped whenever the styled window (or underlying text) changes such that spans
    // need to be reapplied to the EditText's Editable. The view observes this and
    // pulls computeSpans() in response - it never carries the spans itself, since
    // StateFlow diffing large lists on every keystroke would be its own cost.
    private val _spanVersion = MutableStateFlow(0)
    val spanVersion: StateFlow<Int> = _spanVersion

    // Bumped only on externally-driven text replacement (open/new/pattern switch),
    // never on the user's own typing - lets the view distinguish "I need to push
    // this text into the EditText and reset its cursor" from "the EditText already
    // has this text, don't touch it."
    private val _loadVersion = MutableStateFlow(0)
    val loadVersion: StateFlow<Int> = _loadVersion

    private var styledRange: IntRange = 0..0
    private val tokenCache = HashMap<Int, Pair<String, List<Token>>>()
    private var lineStartOffsets: List<Int> = listOf(0)
    private var lines: List<String> = listOf("")

    fun loadPatterns(context: Context) {
        val csv = loadPatternsFromInternal(context)
            ?: context.assets.open(PATTERNS_INTERNAL_PATH).bufferedReader().readText()
        languagePatterns = LanguagePatterns(csv)
        cachedLexerFileName = "\u0000" // force ensureLexerForCurrentFile to re-resolve
        tokenCache.clear()
        _spanVersion.value++
    }

    fun reloadPatterns(context: Context) = loadPatterns(context)

    private fun loadPatternsFromInternal(context: Context): String? {
        val file = context.getFileStreamPath(PATTERNS_INTERNAL_PATH)
        return if (file.exists()) file.readText() else null
    }

    fun toggleWrapLines() {
        _wrapLines.value = !_wrapLines.value
    }

    fun toggleLineNumbers() {
        _showLineNumbers.value = !_showLineNumbers.value
    }

    fun toggleAutoIndent() {
        _autoIndent.value = !_autoIndent.value
    }

    fun openFindBar() {
        _findBarVisible.value = true
        clearMatches()
    }

    fun closeFindBar() {
        _findBarVisible.value = false
        clearMatches()
    }

    fun setFindQuery(query: String) {
        _findQuery.value = query
        // Don't search here - searching happens only when Next is pressed, so typing
        // never scans the document. The stale count/selection from a previous query
        // is cleared so the counter doesn't show results for text that's no longer
        // in the field.
        clearMatches()
    }

    fun setReplaceQuery(query: String) {
        _replaceQuery.value = query
    }

    private val _useRegex = MutableStateFlow(false)
    val useRegex: StateFlow<Boolean> = _useRegex

    fun toggleRegex() {
        _useRegex.value = !_useRegex.value
        // Switching modes changes what the current query even means - a pattern
        // that's a valid regex may not be a sensible literal string and vice versa -
        // so the previous match set (and any error) no longer applies until the next
        // tap of Next re-evaluates the query under the new mode.
        clearMatches()
    }

    private val _regexError = MutableStateFlow<String?>(null)
    val regexError: StateFlow<String?> = _regexError

    // Only ever populated by findNext() - a fresh scan on every tap, since the
    // document may have been edited since the previous tap. Held as a plain field
    // rather than a StateFlow since only its *count* and *current index* need to
    // drive recomposition; the ranges (and, in regex mode, capture groups for
    // replacement) are only read imperatively by the view/replace functions.
    private var matches: List<FindMatch> = emptyList()

    private val _matchCount = MutableStateFlow(0)
    val matchCount: StateFlow<Int> = _matchCount

    private val _currentMatchIndex = MutableStateFlow(-1)
    val currentMatchIndex: StateFlow<Int> = _currentMatchIndex

    // Bumped whenever the view should scroll to and select the current match.
    private val _scrollToMatchVersion = MutableStateFlow(0)
    val scrollToMatchVersion: StateFlow<Int> = _scrollToMatchVersion

    /**
     * A single match's document offsets plus (in regex mode) its capture groups -
     * groupValues[0] is always the whole match, matching Kotlin's MatchResult
     * convention, so $0/$1/$2... in a replacement template line up directly with
     * index into it. Literal (non-regex) matches populate groupValues with just the
     * matched text at index 0, for the same reason, though literal mode's replacement
     * never actually looks at it - see [replaceCurrent]/[replaceAll].
     */
    private data class FindMatch(val range: IntRange, val groupValues: List<String>)

    private fun clearMatches() {
        matches = emptyList()
        _matchCount.value = 0
        _currentMatchIndex.value = -1
        _regexError.value = null
    }

    /**
     * Shifts/drops match ranges to reflect an edit at [start] that replaced [before]
     * characters with [count] new ones, instead of invalidating the whole match set -
     * so ordinary typing elsewhere in the document doesn't force a re-tap of Next.
     * A match entirely before the edit is untouched; one entirely after it shifts by
     * the length delta; one the edit actually touches is dropped, since its content
     * (and therefore whether it's still a match at all) is no longer known without a
     * rescan.
     */
    private fun applyEditToMatches(start: Int, before: Int, count: Int) {
        if (matches.isEmpty()) return
        val editOldEnd = start + before
        val delta = count - before
        val oldCurrent = _currentMatchIndex.value
        var newCurrent = -1
        val updated = ArrayList<FindMatch>(matches.size)
        for ((i, m) in matches.withIndex()) {
            val r = m.range
            val shiftedRange = when {
                r.last < start -> r
                r.first >= editOldEnd -> IntRange(r.first + delta, r.last + delta)
                else -> null
            }
            if (shiftedRange != null) {
                if (i == oldCurrent) newCurrent = updated.size
                updated.add(m.copy(range = shiftedRange))
            }
        }
        matches = updated
        _matchCount.value = matches.size
        _currentMatchIndex.value = newCurrent
    }

    /**
     * Finds every match of [query] in [text], either as a literal case-sensitive
     * substring or (when [useRegex]) as a Kotlin/Java regex. An invalid pattern
     * yields no matches plus a human-readable error instead of throwing - regex
     * syntax errors are an expected, recoverable input state here, not a bug.
     */
    private fun findMatches(query: String, text: String, useRegex: Boolean): Pair<List<FindMatch>, String?> {
        if (query.isEmpty()) return emptyList<FindMatch>() to null
        return if (useRegex) {
            try {
                val regex = Regex(query)
                regex.findAll(text).map { FindMatch(it.range, it.groupValues) }.toList() to null
            } catch (e: Exception) {
                emptyList<FindMatch>() to (e.message ?: "Invalid pattern")
            }
        } else {
            val result = ArrayList<FindMatch>()
            var idx = 0
            while (idx <= text.length) {
                val found = text.indexOf(query, idx, ignoreCase = false)
                if (found == -1) break
                val range = found until (found + query.length)
                result.add(FindMatch(range, listOf(text.substring(found, found + query.length))))
                idx = found + query.length
            }
            result to null
        }
    }

    /**
     * Expands $0, $1, $2... capture-group references in [template] against [match] -
     * $0 is the whole match, following Kotlin's MatchResult.groupValues convention.
     * \$ escapes a literal dollar sign. Only meaningful in regex mode; literal mode's
     * replacement text is used as-is with no special characters (see callers).
     */
    private fun expandReplacement(match: FindMatch, template: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '\\' && i + 1 < template.length && template[i + 1] == '$') {
                sb.append('$')
                i += 2
                continue
            }
            if (c == '$' && i + 1 < template.length && template[i + 1].isDigit()) {
                var j = i + 1
                while (j < template.length && template[j].isDigit()) j++
                val groupIndex = template.substring(i + 1, j).toInt()
                sb.append(match.groupValues.getOrElse(groupIndex) { "" })
                i = j
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** The current match's document offsets, for the view to scroll to and select. */
    fun currentMatchRange(): IntRange? = matches.getOrNull(_currentMatchIndex.value)?.range

    /** Every match found by the last [findNext] scan, for the view to outline. */
    fun allMatchRanges(): List<IntRange> = matches.map { it.range }

    /**
     * Rescans the whole document from scratch (the doc may have changed since the
     * last tap) and jumps to the nearest match at or after [cursorOffset], wrapping
     * to the first match if none is found after it.
     */
    fun findNext(cursorOffset: Int) {
        val (freshMatches, error) = findMatches(_findQuery.value, _text.value, _useRegex.value)
        matches = freshMatches
        _matchCount.value = freshMatches.size
        _regexError.value = error
        if (freshMatches.isEmpty()) {
            _currentMatchIndex.value = -1
            return
        }
        val idx = freshMatches.indexOfFirst { it.range.first >= cursorOffset }.let { if (it == -1) 0 else it }
        _currentMatchIndex.value = idx
        _scrollToMatchVersion.value++
    }

    /**
     * Replaces just the current match with the replace field's text (with $1-style
     * capture-group expansion in regex mode). Reuses the exact same accounting a
     * normal typed edit goes through (dirty flag, line index, spans, and -
     * critically - [applyEditToMatches]) so every *other* match shifts correctly and
     * stays valid; only the just-replaced one is dropped, since its content no longer
     * matches by definition. Lands on the next remaining match (wrapping to the
     * first) so repeated taps of Replace walk forward through the document,
     * mirroring [findNext].
     */
    fun replaceCurrent() {
        val match = matches.getOrNull(_currentMatchIndex.value) ?: return
        val template = _replaceQuery.value
        val replacement = if (_useRegex.value) expandReplacement(match, template) else template
        val old = _text.value
        val start = match.range.first
        val before = match.range.last + 1 - match.range.first
        val newText = old.substring(0, start) + replacement + old.substring(start + before)

        pushProgrammaticEdit(newText)
        _isDirty.value = true
        applyEditToMatches(start, before, replacement.length)

        val replacedEnd = start + replacement.length
        val nextIdx = matches.indexOfFirst { it.range.first >= replacedEnd }
        if (matches.isNotEmpty()) {
            _currentMatchIndex.value = if (nextIdx != -1) nextIdx else 0
            _scrollToMatchVersion.value++
        }
    }

    /**
     * Replaces every match with the replace field's text (with $1-style capture-group
     * expansion per match in regex mode) in one shot. Always rescans from scratch
     * first (rather than trusting the possibly-stale cached [matches]) since this is
     * a bulk, less-frequent operation where correctness matters more than avoiding
     * one extra scan - unlike [replaceCurrent], which runs off whatever match the
     * user is already looking at. Goes through the same full-reset path as opening a
     * file ([setText]): matches are cleared (nothing left to point at until the next
     * tap of Next) and the styled window resets to the top, which is a reasonable
     * trade-off for a whole-document rewrite.
     */
    fun replaceAll() {
        val query = _findQuery.value
        if (query.isEmpty()) return
        val (freshMatches, error) = findMatches(query, _text.value, _useRegex.value)
        _regexError.value = error
        if (freshMatches.isEmpty()) return

        val template = _replaceQuery.value
        val useRegex = _useRegex.value
        val old = _text.value
        val sb = StringBuilder(old.length)
        var cursor = 0
        for (m in freshMatches) {
            val replacement = if (useRegex) expandReplacement(m, template) else template
            sb.append(old, cursor, m.range.first)
            sb.append(replacement)
            cursor = m.range.last + 1
        }
        sb.append(old, cursor, old.length)

        setText(sb.toString())
        _isDirty.value = true
    }

    fun newFile() {
        _currentFileUri.value = null
        _currentFileName.value = null
        _isPatternFile.value = false
        setText("")
        _isDirty.value = false
    }

    fun openFile(context: Context, uri: Uri) {
        val text = context.contentResolver.openInputStream(uri)
            ?.bufferedReader()?.readText() ?: return
        _currentFileUri.value = uri
        _currentFileName.value = queryDisplayName(context, uri)
        _isPatternFile.value = false
        setText(text)
        _isDirty.value = false
    }

    fun openInternalPatterns(context: Context) {
        val file = context.getFileStreamPath(PATTERNS_INTERNAL_PATH)
        if (!file.exists()) {
            val default = context.assets.open(PATTERNS_INTERNAL_PATH).bufferedReader().readText()
            context.openFileOutput(PATTERNS_INTERNAL_PATH, Context.MODE_PRIVATE).writer().use {
                it.write(default)
            }
        }
        _currentFileUri.value = null
        _currentFileName.value = null
        _isPatternFile.value = true
        setText(file.readText())
        _isDirty.value = false
    }

    /**
     * Resolves the human-readable display name for a content:// URI via the
     * ContentResolver, instead of relying on Uri.path (which for providers like
     * the MediaStore-backed document provider returns an opaque document ID such
     * as "msf:123456789" rather than a real filename).
     */
    private fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") {
            return uri.path?.substringAfterLast("/")
        }
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (e: Exception) {
            null
        }
    }

    fun saveFile(context: Context, uri: Uri? = _currentFileUri.value) {
        if (_isPatternFile.value) { saveInternalPatterns(context); return }
        uri ?: return
        context.contentResolver.openOutputStream(uri, "wt")?.writer()?.use {
            it.write(_text.value)
        }
        _isDirty.value = false
    }

    fun saveAs(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.writer()?.use {
            it.write(_text.value)
        }
        _currentFileUri.value = uri
        _currentFileName.value = queryDisplayName(context, uri)
        _isPatternFile.value = false
        _isDirty.value = false
    }

    fun saveInternalPatterns(context: Context) {
        context.openFileOutput(PATTERNS_INTERNAL_PATH, Context.MODE_PRIVATE).writer().use {
            it.write(_text.value)
        }
        _isDirty.value = false
        reloadPatterns(context)
    }

    /**
     * Called by the view's TextWatcher on every edit. The EditText is the source of
     * truth for cursor/selection; this only keeps content (for save/dirty) and the
     * line index (for windowed highlighting) in sync with it. [start]/[before]/[count]
     * describe the raw edit region and are used to shift find-match offsets in place
     * (see [applyEditToMatches]) rather than invalidating them on every keystroke.
     */
    fun onTextChanged(newText: String, start: Int, before: Int, count: Int) {
        if (newText == _text.value) return
        _text.value = newText
        _isDirty.value = true
        recomputeLineIndex()
        _spanVersion.value++
        applyEditToMatches(start, before, count)
    }

    private fun setText(text: String) {
        _text.value = text
        tokenCache.clear()
        recomputeLineIndex()
        val lineCount = lines.size
        styledRange = 0..(WINDOW_MARGIN_LINES * 2).coerceAtMost(lineCount - 1)
        clearMatches()
        _spanVersion.value++
        _loadVersion.value++
    }

    /**
     * Like [setText], but for a single programmatic edit (currently: replaceCurrent)
     * rather than a full document replacement - so it deliberately skips resetting
     * styledRange to the top of the file (which would be a jarring, pointless jump
     * when e.g. replacing a match deep in a large file) and skips clearMatches
     * (the caller drives match bookkeeping itself via applyEditToMatches, since a
     * single edit should only invalidate the one match it touches, not all of them).
     */
    private fun pushProgrammaticEdit(newText: String) {
        _text.value = newText
        tokenCache.clear()
        recomputeLineIndex()
        _spanVersion.value++
        _loadVersion.value++
    }

    private fun recomputeLineIndex() {
        val split = _text.value.split("\n")
        lines = split
        val offsets = ArrayList<Int>(split.size)
        var acc = 0
        for (line in split) {
            offsets.add(acc)
            acc += line.length + 1
        }
        lineStartOffsets = offsets
    }

    /** Same windowing strategy as the Compose version - see updateVisibleRange usage. */
    fun updateVisibleRange(startOffset: Int, endOffset: Int) {
        val lineCount = lineStartOffsets.size
        if (lineCount == 0) return

        val firstLine = lineIndexForOffset(startOffset).coerceIn(0, lineCount - 1)
        val lastLine = lineIndexForOffset(endOffset).coerceIn(0, lineCount - 1)

        val needsUpdate = firstLine < styledRange.first + WINDOW_SAFETY_LINES ||
            lastLine > styledRange.last - WINDOW_SAFETY_LINES

        if (needsUpdate) {
            val paddedFirst = (firstLine - WINDOW_MARGIN_LINES).coerceAtLeast(0)
            val paddedLast = (lastLine + WINDOW_MARGIN_LINES).coerceAtMost(lineCount - 1)
            styledRange = paddedFirst..paddedLast
            pruneCache()
            _spanVersion.value++
        }
    }

    private fun pruneCache() {
        val keepFrom = styledRange.first - CACHE_RETENTION_LINES
        val keepTo = styledRange.last + CACHE_RETENTION_LINES
        val toRemove = tokenCache.keys.filter { it < keepFrom || it > keepTo }
        toRemove.forEach { tokenCache.remove(it) }
    }

    private fun lineIndexForOffset(offset: Int): Int {
        val offsets = lineStartOffsets
        var lo = 0
        var hi = offsets.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (offsets[mid] <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun tokensForLine(index: Int, content: String): List<Token> {
        val cached = tokenCache[index]
        if (cached != null && cached.first == content) return cached.second
        val tokens = lexer.tokenize(content)
        tokenCache[index] = content to tokens
        return tokens
    }

    /** Swaps in the right cached [Lexer] for the current file's full name, if it changed since last call. */
    private fun ensureLexerForCurrentFile() {
        val fileName = _currentFileName.value
        if (fileName != cachedLexerFileName) {
            cachedLexerFileName = fileName
            lexer = languagePatterns.lexerFor(fileName)
            tokenCache.clear() // old tokens were computed under a different rule set
        }
    }

    /**
     * Computes spans for the current styled window only. Pulled by the view whenever
     * spanVersion changes and applied directly onto the EditText's Editable as
     * ForegroundColorSpans - no full-document AnnotatedString rebuild involved.
     */
    fun computeSpans(): List<HighlightSpan> {
        ensureLexerForCurrentFile()
        val currentLines = lines
        val offsets = lineStartOffsets
        val rangeStart = styledRange.first.coerceIn(0, currentLines.size - 1)
        val rangeEnd = styledRange.last.coerceIn(0, currentLines.size - 1)
        if (rangeStart > rangeEnd) return emptyList()

        val spans = ArrayList<HighlightSpan>()
        for (i in rangeStart..rangeEnd) {
            val line = currentLines[i]
            if (line.isEmpty()) continue
            val tokens = tokensForLine(i, line)
            val lineOffset = offsets[i]
            for (token in tokens) {
                spans.add(
                    HighlightSpan(
                        lineOffset + token.start,
                        (lineOffset + token.end).coerceAtMost(lineOffset + line.length),
                        token.color
                    )
                )
            }
        }
        return spans
    }
}
