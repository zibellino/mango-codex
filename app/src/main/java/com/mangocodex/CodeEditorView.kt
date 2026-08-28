package com.mangocodex

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb

private const val FONT_SIZE_SP = 13f
private const val LINE_HEIGHT_SP = 20f

/**
 * Native composite editor surface: a single vertical ScrollView shared by the
 * line-number gutter and the code EditText, so both scroll together as part of the
 * platform's own scroll/draw path - no Compose recomposition sits on the scroll
 * critical path at all. The EditText is wrapped in a HorizontalScrollView so it can
 * scroll sideways independently when line-wrap is off, while the gutter stays pinned.
 */
@SuppressLint("SetTextI18n")
class CodeEditorView(context: Context) : ScrollView(context) {

    val editText: HighlightingEditText = HighlightingEditText(context)

    private val gutter: TextView = TextView(context)
    private val divider: View = View(context)
    private val horizontalScroller: HorizontalScrollView = HorizontalScrollView(context)
    private val row: LinearLayout = LinearLayout(context)

    var onScrollChangedListener: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density

    init {
        isFillViewport = true

        val metrics = context.resources.displayMetrics
        val fontSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, FONT_SIZE_SP, metrics)
        val lineHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LINE_HEIGHT_SP, metrics)
        val extraLineSpacing = (lineHeightPx - fontSizePx * 1.2f).coerceAtLeast(0f)

        gutter.apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_SIZE_SP)
            setTextColor(FG.copy(alpha = 0.4f).toArgb())
            gravity = Gravity.END or Gravity.TOP
            setPadding(0, (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            setLineSpacing(extraLineSpacing, 1f)
            includeFontPadding = false
        }

        divider.setBackgroundColor(0xFF3C3C3C.toInt())

        editText.apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONT_SIZE_SP)
            setTextColor(FG.toArgb())
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            highlightColor = FG.copy(alpha = 0.25f).toArgb()
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            setPadding((4 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            includeFontPadding = false
            setLineSpacing(extraLineSpacing, 1f)
            // wrap_content height + unlimited maxLines means this view lays out its
            // entire content and never scrolls itself - the outer ScrollView does.
            isVerticalScrollBarEnabled = false
        }

        horizontalScroller.apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(
                editText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        row.orientation = LinearLayout.HORIZONTAL
        row.addView(
            gutter,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        row.addView(
            divider,
            LinearLayout.LayoutParams((0.5f * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        )
        row.addView(
            horizontalScroller,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private var lastWrapLines: Boolean? = null

    fun setWrapLines(wrap: Boolean) {
        if (lastWrapLines == wrap) return
        lastWrapLines = wrap
        editText.setHorizontallyScrolling(!wrap)
        val lp = editText.layoutParams as? LinearLayout.LayoutParams
        if (lp != null) {
            lp.width = if (wrap) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT
            editText.layoutParams = lp
        }
        horizontalScroller.scrollTo(0, 0)
    }

    fun setAutoIndent(enabled: Boolean) {
        editText.autoIndentEnabled = enabled
    }

    fun setShowLineNumbers(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        gutter.visibility = visibility
        divider.visibility = visibility
    }

    /** Sizes the gutter to fit [lineCount]'s digit width, so it never needs to reflow the row on scroll. */
    fun updateGutterWidth(lineCount: Int) {
        val digits = lineCount.toString().length.coerceAtLeast(1)
        val sample = "0".repeat(digits)
        val width = gutter.paint.measureText(sample).toInt() + gutter.paddingLeft + gutter.paddingRight
        val lp = gutter.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.width != width) {
            lp.width = width
            gutter.layoutParams = lp
        }
    }

    /**
     * Rebuilds the gutter text so each *visual* (wrapped) line shows the logical line
     * number it starts on, mirroring the original Compose gutter's wrap-aware
     * numbering. Cheap relative to a keystroke, but still O(lines) - call on layout
     * changes (text edits, width/wrap changes), not on every scroll tick.
     */
    fun refreshLineNumbers() {
        val layout = editText.layout ?: return
        val text = editText.text?.toString() ?: return
        val lineCount = layout.lineCount
        if (lineCount == 0) {
            gutter.text = ""
            return
        }
        val startsAtVisualLine = HashMap<Int, Int>()
        var logical = 1
        var offset = 0
        while (true) {
            val clamped = offset.coerceIn(0, text.length)
            val visualLine = layout.getLineForOffset(clamped)
            startsAtVisualLine[visualLine] = logical
            val nextNewline = text.indexOf('\n', clamped)
            if (nextNewline == -1) break
            offset = nextNewline + 1
            logical++
        }
        val sb = StringBuilder()
        for (visualLine in 0 until lineCount) {
            if (visualLine > 0) sb.append('\n')
            sb.append(startsAtVisualLine[visualLine]?.toString() ?: "")
        }
        gutter.text = sb.toString()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrollChangedListener?.invoke()
    }

    /**
     * Approximate visible character-offset range, fed into the same windowed
     * highlighter budget the Compose version used. The gutter/divider/editText all
     * start flush with this ScrollView's content top, so ScrollView scrollY maps
     * directly onto the EditText's own vertical layout coordinates.
     */
    fun visibleOffsetRange(): Pair<Int, Int>? {
        val layout = editText.layout ?: return null
        val docHeight = layout.height + editText.paddingTop + editText.paddingBottom
        val top = (scrollY - editText.paddingTop).coerceIn(0, docHeight)
        val bottom = (scrollY + height - editText.paddingTop).coerceIn(0, docHeight)
        val firstLine = layout.getLineForVertical(top)
        val lastLine = layout.getLineForVertical(bottom).coerceIn(0, layout.lineCount - 1)
        val startOffset = layout.getLineStart(firstLine)
        val endOffset = layout.getLineEnd(lastLine)
        return startOffset to endOffset
    }
}
