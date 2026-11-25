package com.q09009.studytablet.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun EditorArea(vm: AppViewModel, singlePane: Boolean, modifier: Modifier = Modifier) {
    val editor by vm.editor.collectAsState()
    // Zoom state per PDF tab, kept while the app runs so switching tabs keeps the zoom level.
    val zooms = remember { mutableMapOf<String, PdfZoom>() }
    var split by rememberSaveable { mutableFloatStateOf(0.5f) }

    BoxWithConstraints(modifier) {
        val total = constraints.maxWidth.toFloat()
        if (singlePane || editor.panes.size == 1) {
            val i = editor.focused.coerceIn(0, editor.panes.lastIndex)
            PaneView(vm, editor, i, zooms, singlePane, Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize()) {
                PaneView(vm, editor, 0, zooms, false, Modifier.weight(split).fillMaxHeight())
                Box(
                    Modifier
                        .width(12.dp)
                        .fillMaxHeight()
                        .pointerInput(total) {
                            detectHorizontalDragGestures { change, dx ->
                                change.consume()
                                split = (split + dx / total).coerceIn(0.2f, 0.8f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    VerticalDivider()
                    Box(Modifier.size(4.dp, 40.dp).background(MaterialTheme.colorScheme.outlineVariant,
                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                }
                PaneView(vm, editor, 1, zooms, false, Modifier.weight(1f - split).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun PaneView(
    vm: AppViewModel,
    editor: EditorState,
    index: Int,
    zooms: MutableMap<String, PdfZoom>,
    singlePane: Boolean,
    modifier: Modifier,
) {
    val pane = editor.panes.getOrNull(index) ?: Pane()
    val focused = editor.focused == index
    Column(
        modifier.pointerInput(index) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                vm.focusPane(index)
            }
        },
    ) {
        TabStrip(vm, editor, index, pane, focused, singlePane)
        HorizontalDivider()
        val tab = pane.activeTab
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == null) {
                EmptyPane(Modifier.fillMaxSize())
            } else {
                key(tab.key) {
                    when (tab) {
                        is TabContent.Pdf -> PdfTab(vm, index, tab, zooms.getOrPut(tab.key) { PdfZoom() })
                        is TabContent.Text -> TextTab(vm, tab, Modifier.fillMaxSize())
                        is TabContent.Summary -> SummaryTab(vm, tab, Modifier.fillMaxSize())
                        is TabContent.Audio -> AudioTab(tab.uri, tab.name, Modifier.fillMaxSize())
                        TabContent.Recorder -> RecorderTab(vm, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun TabStrip(vm: AppViewModel, editor: EditorState, index: Int, pane: Pane, focused: Boolean, singlePane: Boolean) {
    val active = pane.activeTab?.key
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            pane.tabs.forEach { t ->
                TabChip(vm, index, t, selected = t.key == active, paneFocused = focused)
            }
        }
        if (singlePane && editor.panes.size > 1) {
            IconButton(onClick = { vm.focusPane(1 - editor.focused.coerceIn(0, 1)) }) {
                Text("${editor.focused + 1}/${editor.panes.size}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(vm: AppViewModel, pane: Int, t: TabContent, selected: Boolean, paneFocused: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Box {
        Column(
            Modifier
                .height(44.dp)
                .width(IntrinsicSize.Max)
                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .combinedClickable(onClick = { vm.activateTab(pane, t.key) }, onLongClick = { menu = true }),
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected && paneFocused) accent else Color.Transparent))
            Row(
                Modifier.weight(1f).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tabIcon(t), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(max = 220.dp),
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { vm.closeTab(pane, t.key) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "닫기", Modifier.size(16.dp))
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("다른 쪽 창으로 이동") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null) },
                onClick = { menu = false; vm.moveTab(pane, t.key) })
            DropdownMenuItem(text = { Text("닫기") },
                leadingIcon = { Icon(Icons.Default.Close, null) },
                onClick = { menu = false; vm.closeTab(pane, t.key) })
        }
    }
    VerticalDivider(Modifier.height(44.dp))
}

private fun tabIcon(t: TabContent): ImageVector = when (t) {
    is TabContent.Pdf -> Icons.Default.PictureAsPdf
    is TabContent.Text -> Icons.Default.Description
    is TabContent.Summary -> Icons.Default.AutoAwesome
    is TabContent.Audio -> Icons.Default.AudioFile
    TabContent.Recorder -> Icons.Default.Mic
}

@Composable
private fun EmptyPane(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "왼쪽 탐색기에서 파일을 눌러 여세요.\n길게 누르면 옆 창에 열 수 있습니다.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun Toolbar(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
    HorizontalDivider()
}

@Composable
private fun PdfTab(vm: AppViewModel, pane: Int, tab: TabContent.Pdf, zoom: PdfZoom) {
    val summaries by vm.summaries.collectAsState()
    val running = summaries[tab.uri]?.running == true
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Toolbar {
            IconButton(onClick = zoom::zoomOut) { Icon(Icons.Default.ZoomOut, "축소") }
            Text("${(zoom.value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = zoom::zoomIn) { Icon(Icons.Default.ZoomIn, "확대") }
            IconButton(onClick = zoom::reset) { Icon(Icons.Default.FitScreen, "맞춤") }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { vm.focusPane(pane); vm.summarize(tab) }) {
                if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI 요약")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "더보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("요약 다시 만들기") }, enabled = !running,
                        onClick = { menu = false; vm.focusPane(pane); vm.summarize(tab, regenerate = true) })
                }
            }
        }
        PdfViewer(tab.uri, zoom, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun SummaryTab(vm: AppViewModel, tab: TabContent.Summary, modifier: Modifier) {
    val summaries by vm.summaries.collectAsState()
    val s = summaries[tab.pdf.uri] ?: SummaryState()
    Column(modifier) {
        Toolbar {
            Spacer(Modifier.width(8.dp))
            if (s.running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(s.status ?: if (s.error != null) "요약 실패" else "", Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.running) {
                OutlinedButton(onClick = { vm.cancelSummary(tab.pdf) }) { Text("취소") }
            } else {
                Button(onClick = { vm.summarize(tab.pdf, regenerate = true) }) { Text("다시 시도") }
            }
            Spacer(Modifier.width(4.dp))
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            s.markdown?.let { MarkdownView(it, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun TextTab(vm: AppViewModel, tab: TabContent.Text, modifier: Modifier) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(tab.key) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(tab.uri) {
        try {
            val t = vm.readText(tab.uri)
            text = t; saved = t
            if (editing == null) editing = t.isBlank()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    // Debounced autosave while typing.
    LaunchedEffect(text) {
        val t = text ?: return@LaunchedEffect
        if (t == saved) return@LaunchedEffect
        delay(800)
        vm.saveText(tab.uri, t)
        saved = t
    }
    val latest by rememberUpdatedState(text)
    val latestSaved by rememberUpdatedState(saved)
    DisposableEffect(tab.uri) {
        onDispose { latest?.let { if (it != latestSaved) vm.saveText(tab.uri, it) } }
    }

    Column(modifier) {
        Toolbar {
            IconButton(onClick = { editing = false }) {
                Icon(Icons.Default.Visibility, "미리보기",
                    tint = if (editing == false) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Default.Edit, "편집",
                    tint = if (editing == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    text == null -> "불러오는 중…"
                    text != saved -> "저장 중…"
                    else -> "저장됨"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(enabled = !text.isNullOrBlank(), onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/markdown")
                    .putExtra(Intent.EXTRA_SUBJECT, tab.name.substringBeforeLast('.'))
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "공유"))
            }) { Icon(Icons.Default.Share, "공유") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        val t = text ?: return@Column
        if (editing == true) {
            BasicTextField(
                value = t,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).fillMaxWidth().imePadding()
                    .verticalScroll(rememberScrollState()).padding(20.dp),
            )
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                if (t.isBlank()) {
                    Text("빈 메모입니다. 연필 아이콘을 눌러 작성하세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MarkdownView(t, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
