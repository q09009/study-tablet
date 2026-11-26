package com.q09009.studytablet.record

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * Streams the recorder's PCM into Android's speech recognizer (a long "segmented" session fed through a pipe),
 * so the same audio is both saved and transcribed. Callbacks run on the main thread.
 */
@RequiresApi(33)
class LiveTranscriber(
    private val context: Context,
    private val sampleRate: Int,
    private val languages: List<String>,
    private val onFinal: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onStatus: (String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    // ~60 s of 100 ms chunks; if the recognizer stalls we drop audio for it rather than block recording.
    private val queue = LinkedBlockingQueue<ByteArray>(600)
    @Volatile private var out: OutputStream? = null
    @Volatile private var closing = false
    private var readSide: ParcelFileDescriptor? = null
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private var failures = 0
    private var done: (() -> Unit)? = null
    private var completed = false

    private val writer = Thread({
        while (true) {
            val b = queue.take()
            if (b.isEmpty()) break
            try { out?.write(b) } catch (_: IOException) {}
        }
        runCatching { out?.close() }
    }, "transcriber-pipe")

    fun start() {
        writer.start()
        main.post {
            if (onDevice) ensureModels()
            session()
        }
    }

    /** Asks the system to download any on-device language pack we need that isn't installed yet. */
    private fun ensureModels() {
        val probe = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return
        probe.checkRecognitionSupport(intent(null), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val have = support.installedOnDeviceLanguages + support.pendingOnDeviceLanguages
                languages.filter { l -> have.none { it.equals(l, true) } }.forEach { l ->
                    runCatching { probe.triggerModelDownload(intent(null, l)) }
                }
                main.postDelayed({ probe.destroy() }, 2000)
            }
            override fun onError(error: Int) { probe.destroy() }
        })
    }

    fun feed(pcm: ByteArray, len: Int) {
        if (!closing) queue.offer(pcm.copyOf(len))
    }

    /** Ends the audio stream; [onDone] runs (on the main thread) once the last words are in or after a timeout. */
    fun finish(onDone: () -> Unit) {
        main.post {
            done = onDone
            closing = true
            queue.clear()
            queue.put(ByteArray(0))
            main.postDelayed(::complete, 8000)
        }
    }

    private fun session() {
        if (closing) return complete()
        recognizer?.destroy()
        runCatching { readSide?.close() }
        val pipe = ParcelFileDescriptor.createPipe()
        readSide = pipe[0]
        val old = out
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        runCatching { old?.close() }
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(listener)
        r.startListening(intent(pipe[0]))
    }

    private fun intent(source: ParcelFileDescriptor?, language: String = languages.first()) =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (languages.size > 1 && Build.VERSION.SDK_INT >= 34) {
            // Let the recognizer switch between the given languages mid-session (e.g. Korean lecture with English).
            val allowed = ArrayList(languages)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
        }
        if (source != null) {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
    }

    private fun complete() {
        if (completed) return
        completed = true
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        runCatching { readSide?.close() }
        if (writer.isAlive) queue.offer(ByteArray(0))
        done?.invoke()
    }

    private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { failures = 0; onStatus(null) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) { onPartial(first(partialResults).orEmpty()) }

        override fun onSegmentResults(segmentResults: Bundle) {
            first(segmentResults)?.takeIf { it.isNotEmpty() }?.let(onFinal)
            onPartial("")
        }

        // A recognizer without segmented sessions stops after one utterance; start the next one.
        override fun onResults(results: Bundle?) {
            first(results)?.takeIf { it.isNotEmpty() }?.let(onFinal)
            onPartial("")
            if (closing) complete() else main.post(::session)
        }

        override fun onEndOfSegmentedSession() {
            if (closing) complete() else main.post(::session)
        }

        override fun onError(error: Int) {
            if (closing) return complete()
            if (onDevice && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                // Ask the system to fetch the offline language pack and use the regular recognizer meanwhile.
                runCatching { recognizer?.triggerModelDownload(intent(null)) }
                onDevice = false
                onStatus("기기 내 음성 모델을 내려받는 동안 기본 음성 인식을 사용합니다.")
                main.post(::session)
                return
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                onStatus("음성 인식 권한이 없어 전사를 멈췄습니다. 녹음은 계속됩니다.")
                return
            }
            if (++failures > 5) {
                onStatus("전사를 계속할 수 없습니다 (오류 $error). 녹음은 계속됩니다.")
                return
            }
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onStatus("음성 인식을 다시 연결하는 중… (오류 $error)")
            } else {
                failures = 0
            }
            main.postDelayed(::session, 500)
        }
    }
}
