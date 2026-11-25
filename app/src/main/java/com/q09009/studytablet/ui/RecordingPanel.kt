package com.q09009.studytablet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.q09009.studytablet.record.RecorderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingPanel(vm: AppViewModel, doc: OpenDoc, modifier: Modifier) {
    val context = LocalContext.current
    val recording by RecorderService.state.collectAsState()
    val recError by RecorderService.error.collectAsState()
    val savedCount by RecorderService.savedCount.collectAsState()
    val dir = remember(doc.uri) { vm.store.recordingsDir(doc.uri) }
    var refresh by remember { mutableStateOf(0) }
    val files = remember(dir, savedCount, refresh, recording?.file) {
        dir.listFiles { f -> f.extension == "m4a" }.orEmpty().sortedByDescending { it.lastModified() }
    }

    fun startRecording() {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        RecorderService.start(context, File(dir, name), doc.name)
    }

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) startRecording()
    }

    // Single shared player for this panel.
    val player = remember { PlayerState() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val rec = recording
                if (rec == null) {
                    FilledIconButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) startRecording() else permLauncher.launch(permissions)
                        },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Icon(Icons.Default.Mic, "녹음 시작") }
                    Spacer(Modifier.size(16.dp))
                    Text("강의 녹음 시작", style = MaterialTheme.typography.titleMedium)
                } else {
                    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                    LaunchedEffect(rec) {
                        while (true) { now = SystemClock.elapsedRealtime(); delay(500) }
                    }
                    FilledIconButton(onClick = { RecorderService.stop(context) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Stop, "중지")
                    }
                    Spacer(Modifier.size(8.dp))
                    IconButton(onClick = { RecorderService.togglePause(context) }) {
                        Icon(if (rec.isPaused) Icons.Default.Mic else Icons.Default.Pause, if (rec.isPaused) "재개" else "일시정지")
                    }
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(formatMs(rec.elapsedMs(now)), style = MaterialTheme.typography.headlineSmall)
                        Text(if (rec.isPaused) "일시정지됨" else "녹음 중… (화면을 꺼도 계속됩니다)",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        recError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            LaunchedEffect(it) { delay(5000); RecorderService.clearError() }
        }

        Text("이 문서의 녹음 (${files.size})", style = MaterialTheme.typography.titleSmall)
        var toDelete by remember { mutableStateOf<File?>(null) }
        LazyColumn(Modifier.weight(1f)) {
            items(files, key = { it.name }) { f ->
                val isCurrentRec = recording?.file == f
                RecordingItem(f, player, enabled = !isCurrentRec, onDelete = { toDelete = f })
            }
        }
        toDelete?.let { f ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("녹음 삭제") },
                text = { Text("${f.name} 을(를) 삭제할까요?") },
                confirmButton = {
                    TextButton(onClick = {
                        if (player.file == f) player.release()
                        f.delete(); toDelete = null; refresh++
                    }) { Text("삭제") }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("취소") } },
            )
        }
    }
}

@Composable
private fun RecordingItem(file: File, player: PlayerState, enabled: Boolean, onDelete: () -> Unit) {
    val durationMs by produceState(0L, file, enabled) {
        if (!enabled) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(file.absolutePath)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                }
            }.getOrDefault(0L)
        }
    }
    val active = player.file == file
    val title = runCatching {
        val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(file.nameWithoutExtension)!!
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(d)
    }.getOrDefault(file.nameWithoutExtension)

    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = {
                Text(if (enabled) "${formatMs(durationMs)} · ${file.length() / 1024} KB" else "녹음 중…")
            },
            leadingContent = {
                IconButton(enabled = enabled, onClick = { player.toggle(file) }) {
                    Icon(if (active && player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "재생")
                }
            },
            trailingContent = {
                IconButton(enabled = enabled, onClick = onDelete) { Icon(Icons.Default.Delete, "삭제") }
            },
        )
        if (active) {
            LaunchedEffect(file) {
                while (true) { player.tick(); delay(250) }
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatMs(player.positionMs), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = player.positionMs.toFloat(),
                    onValueChange = { player.seek(it.toLong()) },
                    valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatMs(player.durationMs), style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    var file by mutableStateOf<File?>(null)
    var playing by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)

    fun toggle(f: File) {
        val p = mp
        if (p != null && file == f) {
            if (p.isPlaying) p.pause() else { p.start(); applySpeed() }
            playing = p.isPlaying
            return
        }
        release()
        mp = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            setOnCompletionListener { this@PlayerState.playing = false }
            start()
        }
        file = f
        durationMs = mp!!.duration.toLong()
        applySpeed()
        playing = true
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
        file = null
        playing = false
        positionMs = 0
    }
}

private fun formatMs(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
