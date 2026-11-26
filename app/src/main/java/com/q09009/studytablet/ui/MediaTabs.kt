package com.q09009.studytablet.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.q09009.studytablet.record.RecorderService
import com.q09009.studytablet.record.Transcript
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.delay

@Composable
fun RecorderTab(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recording by RecorderService.state.collectAsState()
    val recError by RecorderService.error.collectAsState()
    val transcript by RecorderService.transcript.collectAsState()
    var transcribe by rememberSaveable { mutableStateOf(Build.VERSION.SDK_INT >= 33) }
    val lang by vm.transcribeLang.collectAsState()

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) vm.startRecording(transcribe)
    }

    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val rec = recording
            if (rec == null) {
                FilledIconButton(
                    onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) vm.startRecording(transcribe) else permLauncher.launch(permissions)
                    },
                    enabled = !transcript.finishing,
                    modifier = Modifier.size(96.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Icon(Icons.Default.Mic, "녹음 시작", Modifier.size(48.dp)) }
                Text("녹음 시작", style = MaterialTheme.typography.titleMedium)
                if (Build.VERSION.SDK_INT >= 33) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("실시간 전사", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = transcribe, onCheckedChange = { transcribe = it })
                    }
                    if (transcribe) {
                        val options = buildList {
                            add("ko" to "한국어")
                            add("en" to "English")
                            if (Build.VERSION.SDK_INT >= 34) add("ko+en" to "한국어 + English")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEach { (value, label) ->
                                FilterChip(selected = lang == value, onClick = { vm.setTranscribeLang(value) },
                                    label = { Text(label) })
                            }
                        }
                        if (lang == "ko+en") Text("말하는 언어를 감지해 문장마다 한국어/영어로 바꿔 적습니다.",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    if (transcribe) "녹음 파일과 전사(…전사.md)는 탐색기에서 선택한 폴더(없으면 작업 폴더)에 저장됩니다."
                    else "녹음 파일은 탐색기에서 선택한 폴더(없으면 작업 폴더)에 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                LaunchedEffect(rec) {
                    while (true) { now = SystemClock.elapsedRealtime(); delay(500) }
                }
                Text(formatMs(rec.elapsedMs(now)), style = MaterialTheme.typography.displayMedium)
                Text(rec.name, style = MaterialTheme.typography.bodyMedium)
                Text(if (rec.isPaused) "일시정지됨" else "녹음 중… (화면을 꺼도 계속됩니다)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { RecorderService.togglePause(context) }, modifier = Modifier.size(64.dp)) {
                        Icon(if (rec.isPaused) Icons.Default.Mic else Icons.Default.Pause,
                            if (rec.isPaused) "재개" else "일시정지", Modifier.size(32.dp))
                    }
                    FilledIconButton(onClick = { RecorderService.stop(context) }, modifier = Modifier.size(80.dp)) {
                        Icon(Icons.Default.Stop, "중지", Modifier.size(40.dp))
                    }
                }
            }
            if (transcript.finishing) Text("마지막 문장을 전사하고 저장하는 중…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            transcript.status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            recError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                LaunchedEffect(it) { delay(8000); RecorderService.clearError() }
            }
        }
        val showTranscript = transcript.available &&
            (recording != null || transcript.finishing || transcript.lines.isNotEmpty())
        if (showTranscript) {
            Spacer(Modifier.height(16.dp))
            TranscriptView(transcript, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun TranscriptView(t: Transcript, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val count = t.lines.size + if (t.partial.isNotEmpty()) 1 else 0
    LaunchedEffect(count, t.partial) { if (count > 0) list.animateScrollToItem(count - 1) }
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if (count == 0) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                Text("말하면 여기에 실시간으로 받아 적습니다.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Surface
        }
        LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(t.lines) { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(RecorderService.stamp(line.atMs), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                    Text(line.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (t.partial.isNotEmpty()) item {
                Text(t.partial, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 52.dp))
            }
        }
    }
}

@Composable
fun AudioTab(uri: Uri, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(uri) { PlayerState() }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    DisposableEffect(uri) {
        runCatching { player.load(context, uri) }.onFailure { error = it.message ?: "재생할 수 없습니다." }
        onDispose { player.release() }
    }
    LaunchedEffect(player) {
        while (true) { player.tick(); delay(250) }
    }

    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatMs(player.positionMs), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = player.positionMs.toFloat(),
                    onValueChange = { player.seek(it.toLong()) },
                    valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(formatMs(player.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.seek((player.positionMs - 10_000).coerceAtLeast(0)) }) {
                    Text("-10s")
                }
                FilledIconButton(onClick = player::toggle, enabled = error == null, modifier = Modifier.size(64.dp)) {
                    Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (player.playing) "일시정지" else "재생", Modifier.size(32.dp))
                }
                IconButton(onClick = { player.seek((player.positionMs + 10_000).coerceAtMost(player.durationMs)) }) {
                    Text("+10s")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1f, 1.25f, 1.5f, 2f).forEach { sp ->
                    TextButton(onClick = { player.changeSpeed(sp) }) {
                        Text("${sp}x", color = if (player.speed == sp) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private class PlayerState {
    private var mp: MediaPlayer? = null
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)

    fun load(context: Context, uri: Uri) {
        mp = MediaPlayer().apply {
            setDataSource(context, uri)
            prepare()
            setOnCompletionListener { this@PlayerState.playing = false }
        }
        durationMs = mp!!.duration.toLong()
    }

    fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) p.pause() else { p.start(); applySpeed() }
        playing = p.isPlaying
    }

    fun tick() {
        mp?.let { positionMs = it.currentPosition.toLong(); playing = it.isPlaying }
    }

    fun seek(ms: Long) {
        mp?.seekTo(ms.toInt()); positionMs = ms
    }

    fun changeSpeed(s: Float) {
        speed = s
        applySpeed()
    }

    private fun applySpeed() {
        val p = mp ?: return
        if (p.isPlaying) runCatching { p.playbackParams = p.playbackParams.setSpeed(speed) }
    }

    fun release() {
        mp?.release()
        mp = null
        playing = false
    }
}

fun formatMs(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
