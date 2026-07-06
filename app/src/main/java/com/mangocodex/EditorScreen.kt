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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
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
    val fieldValue by viewModel.fieldValue.collectAsState()
    val highlighted by viewModel.highlighted.collectAsState()
    val isDirty by viewModel.isDirty.collectAsState()
    val currentUri by viewModel.currentFileUri.collectAsState()
    val wrapLines by viewModel.wrapLines.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.openFile(context, it) } }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let { viewModel.saveAs(context, it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = (currentUri?.path?.substringAfterLast("/") ?: "New file") + (isDirty ? "•" : ""),
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
                                text = { Text("Open file") },
                                onClick = {
                                    openLauncher.launch(arrayOf("*/*"))
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save") },
                                onClick = {
                                    viewModel.saveFile(context)
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
                                    viewModel.openInternalPatterns(context)
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
                        }
                    }
                }
            )
        },
        containerColor = BG
    ) { padding ->
        val displayValue = remember(fieldValue, highlighted) {
            fieldValue.copy(annotatedString = highlighted)
        }

        val density = LocalDensity.current
        val scrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
        var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

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

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BG)
                .onSizeChanged { viewportSize = it }
                .verticalScroll(scrollState)
                .let { if (wrapLines) it else it.horizontalScroll(horizontalScrollState) }
        ) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides noOpBringIntoView) {
                BasicTextField(
                    value = displayValue,
                    onValueChange = { viewModel.onValueChange(it) },
                    onTextLayout = { layoutResult = it },
                    textStyle = TextStyle(
                        color = FG,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(FG),
                    modifier = Modifier
                        .let {
                            if (wrapLines) {
                                it.fillMaxSize()
                            } else {
                                val minWidth = with(density) { viewportSize.width.toDp() }
                                it.fillMaxHeight().widthIn(min = minWidth)
                            }
                        }
                        .focusRequester(focusRequester)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
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
