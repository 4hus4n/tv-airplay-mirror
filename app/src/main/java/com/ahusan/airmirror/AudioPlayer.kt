package com.ahusan.airmirror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays AirPlay audio. Screen mirroring sends AAC-ELD (ct=8); some sources send
 * AAC-LC (ct=4). Both are decoded with MediaCodec and written to an AudioTrack.
 */
class AudioPlayer {

    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var pendingCt = 0
    @Volatile private var volume = 1f
    @Volatile private var flushRequested = false
    private var thread: Thread? = null

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var activeCt = 0
    private var warnedUnsupported = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "AudioPlayer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1000)
        thread = null
        queue.clear()
        release()
    }

    fun setFormat(ct: Int) {
        pendingCt = ct
        queue.clear()
    }

    fun setVolume(v: Float) {
        volume = v
        try { track?.setVolume(v) } catch (_: Exception) {}
    }

    fun flush() {
        queue.clear()
        flushRequested = true
    }

    fun push(data: ByteArray, ct: Int) {
        if (!running) return
        if (ct != 0 && ct != pendingCt) pendingCt = ct
        if (queue.size > 100) queue.clear()   // fell behind: drop stale audio
        queue.offer(data)
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                if (pendingCt != activeCt) {
                    release()
                    activeCt = pendingCt
                    if (activeCt != 0) setup(activeCt)
                }
                if (flushRequested) {
                    flushRequested = false
                    try { codec?.flush() } catch (_: Exception) {}
                    try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
                }
                val frame = queue.poll(10, TimeUnit.MILLISECONDS) ?: continue
                val c = codec ?: continue
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = c.getInputBuffer(idx)!!
                    buf.clear()
                    buf.put(frame, 0, minOf(frame.size, buf.capacity()))
                    c.queueInputBuffer(idx, 0, minOf(frame.size, buf.capacity()), 0, 0)
                }
                drain(c, info)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "audio error", e)
                release()
                activeCt = 0
            }
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            if (out < 0) return
            val buf: ByteBuffer = c.getOutputBuffer(out)!!
            if (info.size > 0) {
                val pcm = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(pcm, 0, info.size)
                track?.write(pcm, 0, pcm.size)
            }
            c.releaseOutputBuffer(out, false)
        }
    }

    private fun setup(ct: Int) {
        val (csd, profile) = when (ct) {
            8 -> byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00) to MediaCodecInfo.CodecProfileLevel.AACObjectELD
            4 -> byteArrayOf(0x12, 0x10) to MediaCodecInfo.CodecProfileLevel.AACObjectLC
            else -> {
                if (!warnedUnsupported) {
                    warnedUnsupported = true
                    Log.w(TAG, "audio type ct=$ct (ALAC/PCM) not supported; mirroring audio uses AAC")
                }
                return
            }
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
        format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(format, null, null, 0)
        c.start()
        codec = c

        val minBuf = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(44100)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(volume)
        t.play()
        track = t
        Log.i(TAG, "audio started ct=$ct decoder=${c.name}")
    }

    private fun release() {
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        track = null
    }

    companion object {
        private const val TAG = "AudioPlayer"
    }
}
