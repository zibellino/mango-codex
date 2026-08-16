package com.mangocodex

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

val BG = Color(0xFF1E1E1E)
val FG = Color(0xFFD4D4D4)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val isDirty by viewModel.isDirty.collectAsState()
    val currentUri by viewModel.currentFileUri.collectAsState()
    val currentFileName by viewModel.currentFileName.collectAsState()
    val wrapLines by viewModel.wrapLines.collectAsState()
    val isPatternFile by viewModel.isPatternFile.collectAsState()
    val showLineNumbers by viewModel.showLineNumbers.collectAsState()
    val highlightEnabled by viewModel.highlightEnabled.collectAsState()
    val highlightSpans by viewModel.highlightSpans.collectAsState()

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

    LaunchedEffect(viewModel.state) {
        snapshotFlow { viewModel.state.text.toString() }
            .distinctUntilChanged()
            .collect {
                viewModel.onTextEdited()
            }
    }

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
                                text = { Text(if (highlightEnabled) "✓ Highlighting" else "Highlighting") },
                                onClick = {
                                    viewModel.toggleHighlighting()
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

        val density = LocalDensity.current
        val textMeasurer = rememberTextMeasurer()
        val scrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

        val gutterTextStyle = TextStyle(
            color = FG.copy(alpha = 0.4f),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )

        val rawText = viewModel.state.text.toString()
        val lineCount = remember(rawText) { rawText.count { it == '\n' } + 1 }

        val gutterWidth = remember(lineCount, density) {
            val digits = lineCount.toString().length
            val measured = textMeasurer.measure("0".repeat(digits), gutterTextStyle)
            with(density) { measured.size.width.toDp() + 8.dp }
        }

        LaunchedEffect(wrapLines) {
            horizontalScrollState.scrollTo(0)
        }

        val noOpBringIntoView = remember {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ) = 0f
            }
        }

        val syntaxHighlightTransformation = remember(highlightSpans) {
            OutputTransformation {
                for ((range, style) in highlightSpans) {
                    if (range.first in 0..length && range.last in 0..length) {
                        addStyle(style, range.first, range.last)
                    }
                }
            }
        }

        val lineNumbersText by remember(rawText) {
            derivedStateOf {
                val result = layoutResult
                if (result == null || result.lineCount == 0) {
                    ""
                } else {
                    val startsAtVisualLine = HashMap<Int, Int>()
                    var logical = 1
                    var offset = 0
                    while (true) {
                        val clamped = offset.coerceIn(0, rawText.length)
                        val visualLine = result.getLineForOffset(clamped)
                        startsAtVisualLine[visualLine] = logical
                        val nextNewline = rawText.indexOf('\n', clamped)
                        if (nextNewline == -1) break
                        offset = nextNewline + 1
                        logical++
                    }
                    (0 until result.lineCount).joinToString("\n") { visualLine ->
                        startsAtVisualLine[visualLine]?.toString() ?: ""
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BG)
        ) {
            if (showLineNumbers) {
                Box(
                    modifier = Modifier
                        .width(gutterWidth)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                ) {
                    BasicText(
                        text = lineNumbersText,
                        style = TextStyle(
                            color = FG.copy(alpha = 0.4f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.End
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 4.dp, top = 4.dp, bottom = 4.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(Color(0xFF3C3C3C))
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .verticalScroll(scrollState)
                    .let { if (wrapLines) it else it.horizontalScroll(horizontalScrollState) }
            ) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides noOpBringIntoView) {
                    BasicTextField(
                        state = viewModel.state,
                        onTextLayout = { layoutResultProvider ->
                            val newResult = layoutResultProvider()
                            if (layoutResult?.lineCount != newResult.lineCount || 
                                layoutResult?.size != newResult.size) {
                                layoutResult = newResult
                            }
                        },
                        outputTransformation = syntaxHighlightTransformation,
                        textStyle = TextStyle(
                            color = FG,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        ),
                        cursorBrush = SolidColor(FG),
                        modifier = Modifier
                            .graphicsLayer() // Isolates cursor draw invalidation to a dedicated layer
                            .let {
                                if (wrapLines) {
                                    it.fillMaxSize()
                                } else {
                                    val minWidth = with(density) { viewportSize.width.toDp() }
                                    it.fillMaxHeight().widthIn(min = minWidth)
                                }
                            }
                            .padding(start = 4.dp, top = 4.dp, end = 8.dp, bottom = 4.dp)
                    )
                }
            }
        }

        LaunchedEffect(scrollState) {
            snapshotFlow { Triple(scrollState.value, viewportSize.height, layoutResult) }
                .distinctUntilChanged()
                .debounce(120)
                .collect { (scrollOffset, viewportHeight, result) ->
                    if (result == null || result.lineCount == 0 || viewportHeight == 0) return@collect
                    val top = scrollOffset.toFloat().coerceAtLeast(0f)
                    val bottom = (scrollOffset + viewportHeight).toFloat()
                        .coerceAtMost(result.size.height.toFloat())
                    val startOffset = result.getOffsetForPosition(Offset(0f, top))
                    val endOffset = result.getOffsetForPosition(Offset(0f, bottom))
                    viewModel.updateVisibleRange(startOffset, endOffset)
                }
        }
    }
}