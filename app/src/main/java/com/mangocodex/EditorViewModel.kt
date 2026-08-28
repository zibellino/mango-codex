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
        lexer = Lexer.fromCsv(csv)
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
     * line index (for windowed highlighting) in sync with it.
     */
    fun onTextChanged(newText: String) {
        if (newText == _text.value) return
        _text.value = newText
        _isDirty.value = true
        recomputeLineIndex()
        _spanVersion.value++
    }

    private fun setText(text: String) {
        _text.value = text
        tokenCache.clear()
        recomputeLineIndex()
        val lineCount = lines.size
        styledRange = 0..(WINDOW_MARGIN_LINES * 2).coerceAtMost(lineCount - 1)
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

    /**
     * Computes spans for the current styled window only. Pulled by the view whenever
     * spanVersion changes and applied directly onto the EditText's Editable as
     * ForegroundColorSpans - no full-document AnnotatedString rebuild involved.
     */
    fun computeSpans(): List<HighlightSpan> {
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
