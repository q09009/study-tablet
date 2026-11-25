package com.q09009.studytablet.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.q09009.studytablet.record.RecorderService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(vm: AppViewModel, doc: OpenDoc, onBack: () -> Unit) {
    val zoom = remember(doc.uri) { PdfZoom() }
    val recording by RecorderService.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                title = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (recording != null) {
                        Icon(Icons.Default.FiberManualRecord, "녹음 중", tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = zoom::zoomOut) { Icon(Icons.Default.ZoomOut, "축소") }
                    IconButton(onClick = zoom::zoomIn) { Icon(Icons.Default.ZoomIn, "확대") }
                    IconButton(onClick = zoom::reset) { Icon(Icons.Default.FitScreen, "100%") }
                },
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    PdfViewer(doc.uri, zoom, Modifier.weight(0.6f).fillMaxHeight())
                    VerticalDivider()
                    SidePanel(vm, doc, Modifier.weight(0.4f).fillMaxHeight())
                }
            } else {
                CompactLayout(vm, doc, zoom)
            }
        }
    }
}

@Composable
private fun CompactLayout(vm: AppViewModel, doc: OpenDoc, zoom: PdfZoom) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("PDF", "요약", "녹음").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> PdfViewer(doc.uri, zoom, Modifier.fillMaxSize())
                1 -> SummaryPanel(vm, doc, Modifier.fillMaxSize())
                else -> RecordingPanel(vm, doc, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SidePanel(vm: AppViewModel, doc: OpenDoc, modifier: Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("AI 요약") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("녹음") })
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) SummaryPanel(vm, doc, Modifier.fillMaxSize())
            else RecordingPanel(vm, doc, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SummaryPanel(vm: AppViewModel, doc: OpenDoc, modifier: Modifier) {
    val s by vm.summary.collectAsState()
    val apiKey by vm.apiKey.collectAsState()
    val context = LocalContext.current

    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(s.status.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = vm::cancelSummary) { Text("취소") }
            } else {
                Button(onClick = vm::summarize, enabled = apiKey != null) {
                    Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (s.markdown == null) "AI 요약 만들기" else "다시 요약")
                }
                Spacer(Modifier.weight(1f))
                if (s.markdown != null) {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/markdown")
                            .putExtra(Intent.EXTRA_SUBJECT, "${doc.name} 요약")
                            .putExtra(Intent.EXTRA_TEXT, s.markdown)
                        context.startActivity(Intent.createChooser(send, "요약 공유"))
                    }) { Icon(Icons.Default.Share, "공유") }
                }
            }
        }
        if (apiKey == null) {
            Text("홈 화면에서 OpenAI API 키를 등록하면 요약을 만들 수 있습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
        s.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        val md = s.markdown
        if (md != null) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState())) {
                MarkdownView(md, Modifier.fillMaxWidth())
            }
        } else if (!s.running) {
            Text("아직 요약이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp))
        }
    }
}
