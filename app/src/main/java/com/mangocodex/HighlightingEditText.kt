package com.mangocodex

import android.content.Context
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import androidx.appcompat.widget.AppCompatEditText
import androidx.compose.ui.graphics.toArgb

/**
 * Plain multi-line EditText used as the code surface, deliberately NOT hosted inside
 * Compose's BasicTextField.
 *
 * Android's TextView/EditText backs its content with a DynamicLayout, which - unlike
 * Compose's Paragraph/MultiParagraph - only re-measures the changed line block on an
 * edit rather than the whole document, and its draw path already clips against the
 * Canvas clip rect so off-screen lines aren't repainted on scroll. Cursor blink and
 * selection are invalidated via small dirty rects owned by the platform widget, not a
 * shared draw scope covering the whole field, so there's no need for a hand-rolled
 * cursor overlay here - the built-in one already behaves the way we want.
 */
class HighlightingEditText(context: Context) : AppCompatEditText(context) {

    /** Fired with the full text on every real (user-driven) edit. */
    var onTextChangedListener: ((String) -> Unit)? = null

    /**
     * When enabled, pressing Enter copies all leading whitespace (spaces/tabs) from
     * the line being split onto the newly created line, so typing continues at the
     * same indentation instead of resetting to column 0.
     */
    var autoIndentEnabled: Boolean = true

    // Guards the watcher against reacting to changes we make ourselves (span
    // application, programmatic setText, auto-indent insertion) so those never
    // bounce back into the ViewModel as if the user had typed them.
    private var suppressWatcher = false

    // Computed in onTextChanged (before the edit is committed to the Editable) and
    // consumed in afterTextChanged (once it's safe to mutate the Editable again).
    private var pendingAutoIndent: String? = null
    private var pendingAutoIndentPos: Int = -1

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingAutoIndent = null
                if (suppressWatcher || !autoIndentEnabled) return
                // Only trigger on a plain single-character newline insertion (a real
                // Enter keypress) - not on multi-line paste or deletions - so we don't
                // second-guess content the user explicitly provided.
                if (s == null || count != 1 || before != 0) return
                if (s[start] != '\n') return

                var lineStart = start
                while (lineStart > 0 && s[lineStart - 1] != '\n') lineStart--

                val whitespace = StringBuilder()
                var i = lineStart
                while (i < start && (s[i] == ' ' || s[i] == '\t')) {
                    whitespace.append(s[i])
                    i++
                }
                if (whitespace.isNotEmpty()) {
                    pendingAutoIndent = whitespace.toString()
                    pendingAutoIndentPos = start + 1
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return

                val indent = pendingAutoIndent
                val pos = pendingAutoIndentPos
                pendingAutoIndent = null
                if (indent != null && s != null && pos in 0..s.length) {
                    suppressWatcher = true
                    try {
                        s.insert(pos, indent)
                        setSelection(pos + indent.length)
                    } finally {
                        suppressWatcher = false
                    }
                }

                onTextChangedListener?.invoke(s?.toString().orEmpty())
            }
        })
    }

    /**
     * Replaces all foreground-color spans across the document with [spans]. Spans are
     * only ever added for the current styled window (see EditorViewModel), so the set
     * being cleared/reapplied here is bounded by that window, not by document length.
     */
    fun applyHighlightSpans(spans: List<HighlightSpan>) {
        val editable = text ?: return
        val length = editable.length

        suppressWatcher = true
        try {
            val existing = editable.getSpans(0, length, ForegroundColorSpan::class.java)
            for (span in existing) editable.removeSpan(span)

            for (s in spans) {
                val start = s.start.coerceIn(0, length)
                val end = s.end.coerceIn(0, length)
                if (start >= end) continue
                editable.setSpan(
                    ForegroundColorSpan(s.color.toArgb()),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } finally {
            suppressWatcher = false
        }
    }

    /** Sets text programmatically (file load/new/pattern switch) without notifying the listener. */
    fun setTextSilently(newText: String) {
        suppressWatcher = true
        try {
            setText(newText)
            setSelection(0)
        } finally {
            suppressWatcher = false
        }
    }
}
