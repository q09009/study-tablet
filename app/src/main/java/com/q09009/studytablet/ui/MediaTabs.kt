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
import kotlinx.coroutines.delay

@Composable
fun RecorderTab(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recording by RecorderService.state.collectAsState()
    val recError by RecorderService.error.collectAsState()

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) vm.startRecording()
    }

    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
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
                        if (granted) vm.startRecording() else permLauncher.launch(permissions)
                    },
                    modifier = Modifier.size(96.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Icon(Icons.Default.Mic, "녹음 시작", Modifier.size(48.dp)) }
                Text("녹음 시작", style = MaterialTheme.typography.titleMedium)
                Text("녹음 파일은 탐색기에서 선택한 폴더(없으면 작업 폴더)에 저장됩니다.",
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
            recError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                LaunchedEffect(it) { delay(8000); RecorderService.clearError() }
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
