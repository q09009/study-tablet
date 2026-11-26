package com.q09009.studytablet.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Encodes mono 16-bit PCM to AAC in an .m4a container. */
class AacWriter(file: File, private val sampleRate: Int) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var samples = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    private val ptsUs get() = samples * 1_000_000 / sampleRate

    fun write(pcm: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) { drain(false); continue }
            val buf = codec.getInputBuffer(i)!!
            buf.clear()
            val n = minOf(buf.remaining(), len - off)
            buf.put(pcm, off, n)
            codec.queueInputBuffer(i, 0, n, ptsUs, 0)
            samples += n / 2
            off += n
            drain(false)
        }
    }

    /** Flushes and closes the file; returns false when nothing was recorded. */
    fun finish(): Boolean {
        try {
            var i = -1
            repeat(100) { if (i < 0) { i = codec.dequeueInputBuffer(10_000); if (i < 0) drain(false) } }
            if (i >= 0) codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            val ok = track >= 0 && runCatching { muxer.stop() }.isSuccess
            runCatching { muxer.release() }
            return ok
        }
    }

    private fun drain(eos: Boolean) {
        var idle = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos || ++idle > 100) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                o >= 0 -> {
                    val out = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
