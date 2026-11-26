package com.q09009.studytablet.record

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.q09009.studytablet.MainActivity
import com.q09009.studytablet.StudyApp
import com.q09009.studytablet.data.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class RecordingState(
    val file: File,
    val dir: Uri,
    val name: String,
    val startedAtElapsed: Long,
    val pausedAtElapsed: Long? = null,
    val pausedTotalMs: Long = 0,
) {
    val isPaused get() = pausedAtElapsed != null
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
        (pausedAtElapsed ?: now) - startedAtElapsed - pausedTotalMs
}

data class TranscriptLine(val atMs: Long, val text: String)

data class Transcript(
    val lines: List<TranscriptLine> = emptyList(),
    val partial: String = "",
    val status: String? = null,
    val available: Boolean = true,
    val finishing: Boolean = false,
)

/** Foreground service so lecture recording (and live transcription) continues with the screen off. */
class RecorderService : Service() {
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    private var writer: AacWriter? = null
    private var transcriber: LiveTranscriber? = null
    private var stopping = false
    private var segmentStartMs: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                Uri.parse(intent.getStringExtra(EXTRA_DIR)!!),
                intent.getStringExtra(EXTRA_NAME)!!,
                intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                intent.getBooleanExtra(EXTRA_TRANSCRIBE, true),
            )
            ACTION_PAUSE -> pauseOrResume()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start(dir: Uri, name: String, title: String, transcribe: Boolean) {
        if (thread != null || stopping) return
        val file = File(cacheDir, "recording-${System.currentTimeMillis()}.m4a")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        file.parentFile?.mkdirs()
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = RATE / 10 * 2
        val rec: AudioRecord
        val aac: AacWriter
        try {
            rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk) * 4,
            )
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "마이크를 열 수 없습니다." }
            aac = AacWriter(file, RATE)
            rec.startRecording()
        } catch (e: Exception) {
            _error.value = "녹음을 시작할 수 없습니다: ${e.message}"
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        writer = aac
        val canTranscribe = Build.VERSION.SDK_INT >= 33
        _transcript.value = Transcript(
            available = canTranscribe && transcribe,
            status = if (!canTranscribe) "실시간 전사는 Android 13 이상에서 지원됩니다. 녹음만 진행합니다." else null,
        )
        val tr = if (canTranscribe && transcribe) newTranscriber() else null
        transcriber = tr
        tr?.start()
        _state.value = RecordingState(file, dir, name, SystemClock.elapsedRealtime())
        running = true
        paused = false
        thread = Thread({
            val buf = ByteArray(chunk)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0 || paused) continue
                    aac.write(buf, n)
                    tr?.feed(buf, n)
                }
            } catch (e: Exception) {
                _error.value = "녹음 중 오류: ${e.message}"
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "recorder").apply { start() }
    }

    @androidx.annotation.RequiresApi(33)
    private fun newTranscriber() = LiveTranscriber(
        this, RATE, "ko-KR",
        onFinal = { text ->
            val at = segmentStartMs ?: _state.value?.elapsedMs() ?: 0
            segmentStartMs = null
            _transcript.update { it.copy(lines = it.lines + TranscriptLine(at, text), partial = "") }
        },
        onPartial = { p ->
            if (p.isNotEmpty() && segmentStartMs == null) segmentStartMs = _state.value?.elapsedMs()
            _transcript.update { it.copy(partial = p) }
        },
        onStatus = { s -> _transcript.update { it.copy(status = s) } },
    )

    private fun pauseOrResume() {
        val s = _state.value ?: return
        val now = SystemClock.elapsedRealtime()
        _state.value = if (s.isPaused) {
            paused = false
            s.copy(pausedAtElapsed = null, pausedTotalMs = s.pausedTotalMs + (now - s.pausedAtElapsed!!))
        } else {
            paused = true
            s.copy(pausedAtElapsed = now)
        }
    }

    private fun stopRecording() {
        val s = _state.value ?: return
        if (stopping) return
        stopping = true
        running = false
        _state.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val t = thread
        val aac = writer
        thread = null
        writer = null
        val tr = transcriber
        transcriber = null
        Thread {
            t?.join(3000)
            val ok = aac?.let { runCatching { it.finish() }.getOrDefault(false) } ?: false
            if (tr == null) save(s, ok)
            else {
                _transcript.update { it.copy(finishing = true) }
                tr.finish { Thread { save(s, ok) }.start() }
            }
        }.start()
    }

    private fun save(s: RecordingState, ok: Boolean) {
        val ws = Workspace(this)
        if (!ok) {
            s.file.delete()
        } else {
            try {
                ws.importFileBlocking(s.dir, s.file, s.name, "audio/mp4")
                s.file.delete()
            } catch (e: Exception) {
                val backup = File(getExternalFilesDir(null), s.name)
                runCatching { s.file.copyTo(backup, overwrite = true); s.file.delete() }
                _error.value = "폴더에 저장하지 못해 앱 폴더에 보관했습니다: ${backup.absolutePath}"
            }
        }
        val lines = _transcript.value.lines
        if (lines.isNotEmpty()) {
            val base = s.name.removeSuffix(".m4a")
            val md = buildString {
                append("# ").append(base).append(" 전사\n\n")
                lines.forEach { append("**[").append(stamp(it.atMs)).append("]** ").append(it.text).append("\n\n") }
            }
            val tmp = File(cacheDir, "transcript-${System.currentTimeMillis()}.md")
            try {
                tmp.writeText(md)
                ws.importFileBlocking(s.dir, tmp, "$base 전사.md", "text/markdown")
            } catch (e: Exception) {
                _error.value = "전사를 저장하지 못했습니다: ${e.message}"
            } finally {
                tmp.delete()
            }
        }
        _transcript.update { it.copy(finishing = false) }
        _saved.value = s.dir
        _savedCount.value++
        stopping = false
        stopSelf()
    }

    override fun onDestroy() {
        if (_state.value != null) stopRecording()
        super.onDestroy()
    }

    private fun notification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, StudyApp.RECORDING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("강의 녹음 중")
            .setContentText(title)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "중지", stop)
            .build()
    }

    companion object {
        private const val RATE = 16_000
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_DIR = "dir"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TRANSCRIBE = "transcribe"
        private const val NOTIFICATION_ID = 42

        fun stamp(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
            else "%02d:%02d".format(s / 60, s % 60)
        }

        private val _state = MutableStateFlow<RecordingState?>(null)
        val state: StateFlow<RecordingState?> = _state

        private val _transcript = MutableStateFlow(Transcript())
        /** Live transcript of the current (or last) recording. */
        val transcript: StateFlow<Transcript> = _transcript

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error

        /** Increments whenever a recording finishes, so lists can refresh. */
        private val _savedCount = MutableStateFlow(0)
        val savedCount: StateFlow<Int> = _savedCount

        private val _saved = MutableStateFlow<Uri?>(null)
        /** Folder that received the most recently saved recording. */
        val saved: StateFlow<Uri?> = _saved

        fun clearError() { _error.value = null }

        fun start(context: Context, dir: Uri, name: String, title: String, transcribe: Boolean = true) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_DIR, dir.toString()).putExtra(EXTRA_NAME, name).putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TRANSCRIBE, transcribe),
            )
        }

        fun togglePause(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_PAUSE))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
