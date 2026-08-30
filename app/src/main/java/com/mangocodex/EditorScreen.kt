package com.mangocodex

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

val BG = Color(0xFF1E1E1E)
val FG = Color(0xFFD4D4D4)

// Outline color for find matches - distinct from both the syntax-highlight palette
// and the current match's native (blue-ish) text selection color. Partial alpha and
// a softer amber (rather than solid, saturated orange) so it reads as a subtle marker
// rather than competing with the syntax highlighting for attention.
private val MATCH_BORDER_COLOR = Color(0xB3FFB300).toArgb()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val text by viewModel.text.collectAsState()
    val isDirty by viewModel.isDirty.collectAsState()
    val currentUri by viewModel.currentFileUri.collectAsState()
    val currentFileName by viewModel.currentFileName.collectAsState()
    val wrapLines by viewModel.wrapLines.collectAsState()
    val isPatternFile by viewModel.isPatternFile.collectAsState()
    val showLineNumbers by viewModel.showLineNumbers.collectAsState()
    val autoIndent by viewModel.autoIndent.collectAsState()
    val findBarVisible by viewModel.findBarVisible.collectAsState()
    val findQuery by viewModel.findQuery.collectAsState()
    val replaceQuery by viewModel.replaceQuery.collectAsState()
    val useRegex by viewModel.useRegex.collectAsState()
    val matchCount by viewModel.matchCount.collectAsState()
    val currentMatchIndex by viewModel.currentMatchIndex.collectAsState()
    val scrollToMatchVersion by viewModel.scrollToMatchVersion.collectAsState()
    val spanVersion by viewModel.spanVersion.collectAsState()
    val loadVersion by viewModel.loadVersion.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var pendingDiscardAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun runOrConfirmDiscard(action: () -> Unit) {
        if (isDirty) {
            pendingDiscardAction = action
        } else {
            action()
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.openFile(context, it) } }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? -> uri?.let { viewModel.saveAs(context, it) } }

    // Tracks which loadVersion is currently reflected in the live EditText, so the
    // AndroidView update block only pushes viewModel.text -> EditText on real file
    // loads (open/new/pattern switch/reload), never in reaction to the user's own
    // typing - the EditText already IS the source of that text, so re-setting it
    // would just reset the cursor mid-keystroke.
    var appliedLoadVersion by remember { mutableStateOf(-1) }

    // Captured so the "Next" button can read the *current* cursor position at tap
    // time - find navigation is driven by where the cursor actually is right now,
    // not by any state the ViewModel tracks separately.
    var editorViewRef by remember { mutableStateOf<CodeEditorView?>(null) }

    // Tracks which scrollToMatchVersion has already been revealed, so a match is only
    // selected/scrolled-to once per actual find-navigation event - not on every
    // recomposition of this update block (which also fires on unrelated scroll/edit
    // activity via spanVersion). Without this guard, any scroll while the find bar is
    // open would re-select and re-scroll back to the last match, and would keep
    // stealing focus away from wherever the user just tapped or typed.
    var appliedMatchVersion by remember { mutableStateOf(-1) }

    // The find bar's actual measured height (it sits above the keyboard via its own
    // imePadding), fed into the editor view so scrollToOffset knows how much of the
    // bottom of the viewport is obstructed and doesn't reveal a match behind it.
    var findBarHeightPx by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (currentFileName ?: "New file") + (if (isDirty) "•" else ""),
                        color = FG,
                        fontFamily = FontFamily.Monospace
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF252526)),
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Text("⋮", color = FG, fontSize = 20.sp)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New file") },
                                onClick = {
                                    runOrConfirmDiscard { viewModel.newFile() }
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Open file") },
                                onClick = {
                                    runOrConfirmDiscard { openLauncher.launch(arrayOf("*/*")) }
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save") },
                                onClick = {
                                    if (currentUri == null && !isPatternFile) {
                                        saveLauncher.launch("untitled.txt")
                                    } else {
                                        viewModel.saveFile(context)
                                    }
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save as…") },
                                onClick = {
                                    saveLauncher.launch("untitled.txt")
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (findBarVisible) "✓ Find/Replace" else "Find/Replace") },
                                onClick = {
                                    if (findBarVisible) viewModel.closeFindBar() else viewModel.openFindBar()
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Edit patterns") },
                                onClick = {
                                    runOrConfirmDiscard { viewModel.openInternalPatterns(context) }
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reload patterns") },
                                onClick = {
                                    viewModel.reloadPatterns(context)
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(if (wrapLines) "✓ Wrap lines" else "Wrap lines") },
                                onClick = {
                                    viewModel.toggleWrapLines()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (showLineNumbers) "✓ Line numbers" else "Line numbers") },
                                onClick = {
                                    viewModel.toggleLineNumbers()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (autoIndent) "✓ Auto-indent" else "Auto-indent") },
                                onClick = {
                                    viewModel.toggleAutoIndent()
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            )
        },
        containerColor = BG
    ) { padding ->
        pendingDiscardAction?.let { action ->
            AlertDialog(
                onDismissRequest = { pendingDiscardAction = null },
                title = { Text("Discard changes?") },
                text = { Text("You have unsaved changes. Discarding them cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDiscardAction = null
                        action()
                    }) {
                        Text("Discard")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDiscardAction = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CodeEditorView(ctx).apply {
                        setBackgroundColor(BG.toArgb())

                        editText.setTextSilently(text)
                        appliedLoadVersion = loadVersion

                        setWrapLines(wrapLines)
                        setShowLineNumbers(showLineNumbers)
                        setAutoIndent(autoIndent)
                        updateGutterWidth(text.count { it == '\n' } + 1)

                        editText.onTextChangedListener = { newText, start, before, count ->
                            viewModel.onTextChanged(newText, start, before, count)
                        }
                        editText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            // Layout changed (text edit reflow, width change, wrap toggle) -
                            // resync the gutter to match the new line positions.
                            refreshLineNumbers()
                        }

                        val handler = Handler(Looper.getMainLooper())
                        var pendingScrollUpdate: Runnable? = null
                        onScrollChangedListener = {
                            pendingScrollUpdate?.let { handler.removeCallbacks(it) }
                            val job = Runnable {
                                visibleOffsetRange()?.let { (start, end) ->
                                    viewModel.updateVisibleRange(start, end)
                                }
                            }
                            pendingScrollUpdate = job
                            handler.postDelayed(job, 120)
                        }

                        // A layout pass has to happen first before spans/gutter numbers
                        // can be computed against real line positions.
                        post {
                            refreshLineNumbers()
                            editText.applyHighlightSpans(viewModel.computeSpans())
                        }

                        editText.requestFocus()
                    }
                        .also { editorViewRef = it }
                },
                update = { view ->
                    if (loadVersion != appliedLoadVersion) {
                        view.editText.setTextSilently(text)
                        appliedLoadVersion = loadVersion
                        view.post {
                            view.refreshLineNumbers()
                            view.editText.applyHighlightSpans(viewModel.computeSpans())
                        }
                    }

                    view.setWrapLines(wrapLines)
                    view.setShowLineNumbers(showLineNumbers)
                    view.setAutoIndent(autoIndent)
                    view.updateGutterWidth(text.count { it == '\n' } + 1)

                    // Reading spanVersion here is what makes this update block - and
                    // therefore the span reapplication below - rerun whenever the
                    // ViewModel bumps it, whether from a keystroke or a scroll-triggered
                    // window shift.
                    @Suppress("UNUSED_EXPRESSION")
                    spanVersion
                    view.editText.applyHighlightSpans(viewModel.computeSpans())

                    // Reading scrollToMatchVersion here is what makes this update block
                    // rerun on every find-navigation jump (typing a new query, opening
                    // the bar, or pressing Next). appliedMatchVersion then gates the
                    // actual reveal so it only happens once per real jump, not on every
                    // recomposition this block happens to run for.
                    @Suppress("UNUSED_EXPRESSION")
                    scrollToMatchVersion
                    if (findBarVisible) {
                        view.setBottomOverlayHeight(findBarHeightPx)
                        // Repainting borders has no focus/selection side effects, so -
                        // unlike revealMatch - it's safe to just do on every
                        // recomposition rather than gate it on appliedMatchVersion.
                        view.applyMatchBorders(viewModel.allMatchRanges(), MATCH_BORDER_COLOR)
                        if (scrollToMatchVersion != appliedMatchVersion) {
                            appliedMatchVersion = scrollToMatchVersion
                            viewModel.currentMatchRange()?.let { range -> view.revealMatch(range) }
                        }
                    } else {
                        view.setBottomOverlayHeight(0)
                        view.applyMatchBorders(emptyList(), MATCH_BORDER_COLOR)
                    }
                }
            )

            // Find/Replace bar - find is wired up (matches, counter, Next); regex and
            // replace/replace-all are still no-ops, next steps.
            if (findBarVisible) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding()
                        .onGloballyPositioned { coordinates -> findBarHeightPx = coordinates.size.height },
                    color = Color(0xFF252526),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            CompactTextField(
                                value = findQuery,
                                onValueChange = { viewModel.setFindQuery(it) },
                                placeholder = "Find",
                                modifier = Modifier.weight(1f),
                                trailing = {
                                    Text(
                                        text = if (findQuery.isEmpty()) "" else "${(currentMatchIndex + 1).coerceAtLeast(0)}/$matchCount",
                                        color = FG.copy(alpha = 0.5f),
                                        fontSize = 12.sp
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            CompactButton(
                                onClick = {
                                    val cursor = editorViewRef?.editText?.selectionEnd ?: 0
                                    viewModel.findNext(cursor)
                                },
                                modifier = Modifier.width(64.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(" Next", fontSize = 12.sp)
                            }
                            CompactButton(
                                onClick = { viewModel.toggleRegex() },
                                modifier = Modifier.width(40.dp),
                                backgroundColor = if (useRegex) Color(0xFF5A5A62) else Color.Transparent
                            ) {
                                Text(".*", fontSize = 12.sp, color = FG)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            CompactTextField(
                                value = replaceQuery,
                                onValueChange = { viewModel.setReplaceQuery(it) },
                                placeholder = "Replace",
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            CompactButton(
                                onClick = { /* TODO: replace current match */ },
                                modifier = Modifier.width(64.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(" Replace", fontSize = 12.sp)
                            }
                            CompactButton(
                                onClick = { /* TODO: replace all matches */ },
                                modifier = Modifier.width(40.dp)
                            ) {
                                Text("All", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A text field with tighter chrome than Material3's OutlinedTextField (whose default
 * label/padding makes it too tall for a compact find/replace bar), and a fixed
 * on-screen height regardless of content. Multi-line input (typed newlines or a
 * multi-line paste) is fully accepted - the field just becomes an internally
 * scrollable one-row-tall window into it, via verticalScroll, rather than growing the
 * bar or clipping/stripping the extra lines outright the way singleLine = true would.
 *
 * The exact height and clipToBounds() live directly on this Row (the component's own
 * root), rather than being left to propagate down through inner Box/BasicTextField
 * layers - that propagation turned out not to reliably bound BasicTextField's own
 * measurement when singleLine = false, letting it (and this whole component, and
 * everything below it in the find bar) grow instead of scrolling internally.
 * clipToBounds() is the hard backstop: even if the field's internal measurement still
 * wants more height than 32dp, it's forcibly cut off right here instead of pushing
 * the rest of the bar down.
 */
@Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .height(32.dp)
            .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(4.dp))
            .clipToBounds()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (value.isEmpty()) {
                Text(placeholder, color = FG.copy(alpha = 0.4f), fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = false,
                textStyle = TextStyle(color = FG, fontSize = 14.sp),
                cursorBrush = SolidColor(FG),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(6.dp))
            trailing()
        }
    }
}

/**
 * A small clickable chip used for the find/replace bar's action buttons. Plain
 * TextButton enforces Material's ~40dp minimum touch target, which is too tall for
 * this bar, so this gives full control over height/width instead.
 */
@Composable
private fun CompactButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .height(32.dp)
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = contentAlignment
    ) {
        content()
    }
}
