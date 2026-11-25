package com.q09009.studytablet.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.q09009.studytablet.MainActivity
import com.q09009.studytablet.StudyApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class RecordingState(
    val file: File,
    val startedAtElapsed: Long,
    val pausedAtElapsed: Long? = null,
    val pausedTotalMs: Long = 0,
) {
    val isPaused get() = pausedAtElapsed != null
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
        (pausedAtElapsed ?: now) - startedAtElapsed - pausedTotalMs
}

/** Foreground service so lecture recording continues with the screen off or app in background. */
class RecorderService : Service() {
    private var recorder: MediaRecorder? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(File(intent.getStringExtra(EXTRA_PATH)!!), intent.getStringExtra(EXTRA_TITLE).orEmpty())
            ACTION_PAUSE -> pauseOrResume()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun start(file: File, title: String) {
        if (recorder != null) return
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(title),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        file.parentFile?.mkdirs()
        val r = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(96_000)
            setAudioSamplingRate(44_100)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
        }
        try {
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            _error.value = "녹음을 시작할 수 없습니다: ${e.message}"
            stopSelf()
            return
        }
        recorder = r
        _state.value = RecordingState(file, SystemClock.elapsedRealtime())
    }

    private fun pauseOrResume() {
        val r = recorder ?: return
        val s = _state.value ?: return
        val now = SystemClock.elapsedRealtime()
        _state.value = if (s.isPaused) {
            r.resume()
            s.copy(pausedAtElapsed = null, pausedTotalMs = s.pausedTotalMs + (now - s.pausedAtElapsed!!))
        } else {
            r.pause()
            s.copy(pausedAtElapsed = now)
        }
    }

    private fun stopRecording() {
        recorder?.let { r ->
            runCatching { r.stop() }.onFailure { _state.value?.file?.delete() }
            r.release()
        }
        recorder = null
        _state.value = null
        _savedCount.value++
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (recorder != null) stopRecording()
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
        private const val ACTION_START = "start"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val NOTIFICATION_ID = 42

        private val _state = MutableStateFlow<RecordingState?>(null)
        val state: StateFlow<RecordingState?> = _state

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error

        /** Increments whenever a recording finishes, so lists can refresh. */
        private val _savedCount = MutableStateFlow(0)
        val savedCount: StateFlow<Int> = _savedCount

        fun clearError() { _error.value = null }

        fun start(context: Context, file: File, title: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecorderService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_PATH, file.absolutePath).putExtra(EXTRA_TITLE, title),
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
