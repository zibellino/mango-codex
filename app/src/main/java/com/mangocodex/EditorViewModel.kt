package com.mangocodex

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val PATTERNS_INTERNAL_PATH = "patterns.csv"

private const val WINDOW_MARGIN_LINES = 150
private const val WINDOW_SAFETY_LINES = WINDOW_MARGIN_LINES / 3
private const val CACHE_RETENTION_LINES = WINDOW_MARGIN_LINES * 3

class EditorViewModel : ViewModel() {

    private var lexer: Lexer = Lexer(emptyList())

    // Single source of text/selection truth with BTF2 buffer state
    val state = TextFieldState("")

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

    private val _isPatternFile = MutableStateFlow(false)
    val isPatternFile: StateFlow<Boolean> = _isPatternFile

    private val _highlightEnabled = MutableStateFlow(true)
    val highlightEnabled: StateFlow<Boolean> = _highlightEnabled

    // Expose current line spans to OutputTransformation dynamically
    private val _highlightSpans = MutableStateFlow<List<Pair<IntRange, SpanStyle>>>(emptyList())
    val highlightSpans: StateFlow<List<Pair<IntRange, SpanStyle>>> = _highlightSpans

    private var styledRange: IntRange = 0..0
    private val tokenCache = HashMap<Int, Pair<String, List<Token>>>()
    private var lineStartOffsets: List<Int> = listOf(0)

    fun loadPatterns(context: Context) {
        val csv = loadPatternsFromInternal(context)
            ?: context.assets.open(PATTERNS_INTERNAL_PATH).bufferedReader().readText()
        lexer = Lexer.fromCsv(csv)
        tokenCache.clear()
        rehighlight()
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

    fun toggleHighlighting() {
        _highlightEnabled.value = !_highlightEnabled.value
        rehighlight()
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
            it.write(state.text.toString())
        }
        _isDirty.value = false
    }

    fun saveAs(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.writer()?.use {
            it.write(state.text.toString())
        }
        _currentFileUri.value = uri
        _currentFileName.value = queryDisplayName(context, uri)
        _isPatternFile.value = false
        _isDirty.value = false
    }

    fun saveInternalPatterns(context: Context) {
        context.openFileOutput(PATTERNS_INTERNAL_PATH, Context.MODE_PRIVATE).writer().use {
            it.write(state.text.toString())
        }
        _isDirty.value = false
        reloadPatterns(context)
    }

    fun onTextEdited() {
        _isDirty.value = true
        rehighlight()
    }

    private fun setText(text: String) {
        state.setTextAndPlaceCursorAtEnd(text)
        tokenCache.clear()
        val lineCount = text.count { it == '\n' } + 1
        styledRange = 0..(WINDOW_MARGIN_LINES * 2).coerceAtMost(lineCount - 1)
        rehighlight()
    }

    fun updateVisibleRange(startOffset: Int, endOffset: Int) {
        if (!_highlightEnabled.value) return

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
            rehighlight()
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

    private fun rehighlight() {
        val currentText = state.text.toString()

        if (!_highlightEnabled.value) {
            lineStartOffsets = listOf(0)
            _highlightSpans.value = emptyList()
            return
        }

        val lines = currentText.split("\n")

        val offsets = ArrayList<Int>(lines.size)
        var acc = 0
        for (line in lines) {
            offsets.add(acc)
            acc += line.length + 1
        }
        lineStartOffsets = offsets

        val rangeStart = styledRange.first.coerceIn(0, lines.size - 1)
        val rangeEnd = styledRange.last.coerceIn(0, lines.size - 1)

        val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
        if (rangeStart <= rangeEnd) {
            for (i in rangeStart..rangeEnd) {
                val line = lines[i]
                if (line.isEmpty()) continue
                val tokens = tokensForLine(i, line)
                val lineOffset = offsets[i]
                for (token in tokens) {
                    val start = lineOffset + token.start
                    val end = (lineOffset + token.end).coerceAtMost(lineOffset + line.length)
                    spans.add(start..end to SpanStyle(color = token.color))
                }
            }
        }
        _highlightSpans.value = spans
    }
}