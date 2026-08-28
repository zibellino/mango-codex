package com.mangocodex

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

val BG = Color(0xFF1E1E1E)
val FG = Color(0xFFD4D4D4)

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
                    IconButton(onClick = { viewModel.openFindBar() }) {
                        Text("🔍", fontSize = 16.sp)
                    }
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

                        editText.onTextChangedListener = { newText -> viewModel.onTextChanged(newText) }
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
                }
            )

            // Step 1 of find/replace: bar shell only, no search logic wired up yet.
            if (findBarVisible) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .imePadding(),
                    color = Color(0xFF252526),
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Find/Replace (coming soon)",
                            color = FG,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.closeFindBar() }) {
                            Text("✕", color = FG, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
