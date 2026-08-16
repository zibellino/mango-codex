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

    // Guards the watcher against reacting to changes we make ourselves (span
    // application, programmatic setText) so those never bounce back into the
    // ViewModel as if the user had typed them.
    private var suppressWatcher = false

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
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
