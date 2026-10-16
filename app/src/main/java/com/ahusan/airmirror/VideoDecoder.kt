package com.ahusan.airmirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the H.264 (Annex-B) mirroring stream straight onto a Surface
 * with MediaCodec, rendering each frame as soon as it is decoded (lowest latency).
 */
class VideoDecoder(private val callback: Callback) {

    interface Callback {
        fun onFirstFrame()
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    private class Packet(val data: ByteArray, val ptsUs: Long)

    private val queue = LinkedBlockingQueue<Packet>()
    private val lock = Object()
    @Volatile private var surface: Surface? = null
    @Volatile private var running = false
    @Volatile private var flushRequested = false
    private var thread: Thread? = null

    private var codec: MediaCodec? = null
    private var currentSps: ByteArray? = null
    private var lastKeyPacket: Packet? = null
    private var waitForKeyFrame = true
    private var firstFrameSent = false
    private var maxWidth = 1920
    private var maxHeight = 1080

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "VideoDecoder").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1000)
        thread = null
        queue.clear()
        releaseCodec()
        currentSps = null
        lastKeyPacket = null
    }

    fun setSurface(s: Surface?) {
        synchronized(lock) {
            surface = s
        }
        // codec must be rebuilt for the new surface; replay the last keyframe
        flushRequested = true
        lastKeyPacket?.let { if (s != null) queue.offer(it) }
    }

    fun setSourceSize(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            maxWidth = maxOf(w, 1920)
            maxHeight = maxOf(h, 1080)
        }
    }

    /** New session or stream discontinuity: wait for the next SPS/IDR. */
    fun reset() {
        queue.clear()
        flushRequested = true
        lastKeyPacket = null
        waitForKeyFrame = true
        firstFrameSent = false
    }

    fun push(data: ByteArray, ptsUs: Long) {
        if (!running) return
        // if the decoder falls behind, drop everything up to the next keyframe
        if (queue.size > 90) {
            queue.clear()
            waitForKeyFrame = true
        }
        queue.offer(Packet(data, ptsUs))
    }

    // ---------------------------------------------------------------------

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                if (flushRequested) {
                    flushRequested = false
                    releaseCodec()
                }
                val pkt = queue.poll(10, TimeUnit.MILLISECONDS)
                if (pkt != null) handlePacket(pkt)
                drain(info)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "decoder error, restarting codec", e)
                releaseCodec()
                waitForKeyFrame = true
            }
        }
    }

    private fun handlePacket(pkt: Packet) {
        val nals = splitNals(pkt.data)
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var isKey = false
        for (n in nals) {
            if (n.isEmpty()) continue
            when (n[0].toInt() and 0x1f) {
                7 -> sps = n
                8 -> pps = n
                5 -> isKey = true
            }
        }
        if (sps != null) isKey = true
        if (isKey) {
            lastKeyPacket = pkt
            waitForKeyFrame = false
        }
        if (waitForKeyFrame) return

        val s = surface ?: return
        if (sps != null && pps != null && (codec == null || !sps.contentEquals(currentSps))) {
            configure(s, sps, pps)
        }
        val c = codec ?: return

        val index = c.dequeueInputBuffer(20_000)
        if (index < 0) return   // decoder busy: frame dropped
        val buf: ByteBuffer = c.getInputBuffer(index) ?: return
        buf.clear()
        if (pkt.data.size > buf.capacity()) {
            Log.w(TAG, "frame too large (${pkt.data.size}), skipping")
            c.queueInputBuffer(index, 0, 0, pkt.ptsUs, 0)
            return
        }
        buf.put(pkt.data)
        val flags = if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        c.queueInputBuffer(index, 0, pkt.data.size, pkt.ptsUs, flags)
    }

    private fun drain(info: MediaCodec.BufferInfo) {
        val c = codec ?: return
        while (true) {
            val out = c.dequeueOutputBuffer(info, 0)
            when {
                out >= 0 -> {
                    c.releaseOutputBuffer(out, true)
                    if (!firstFrameSent) {
                        firstFrameSent = true
                        callback.onFirstFrame()
                    }
                }
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportFormat(c.outputFormat)
                else -> return
            }
        }
    }

    private fun reportFormat(f: MediaFormat) {
        var w = f.getInteger(MediaFormat.KEY_WIDTH)
        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
        if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
            w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
            h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
        }
        Log.i(TAG, "video size ${w}x$h")
        callback.onVideoSizeChanged(w, h)
    }

    private fun configure(s: Surface, sps: ByteArray, pps: ByteArray) {
        releaseCodec()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, maxWidth, maxHeight)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(START + sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(START + pps))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)   // realtime
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(format, s, null, 0)
        } catch (e: Exception) {
            // some decoders reject KEY_LOW_LATENCY / priority: retry plainly
            Log.w(TAG, "configure failed, retrying without low-latency keys", e)
            c.reset()
            val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, maxWidth, maxHeight)
            plain.setByteBuffer("csd-0", ByteBuffer.wrap(START + sps))
            plain.setByteBuffer("csd-1", ByteBuffer.wrap(START + pps))
            plain.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            c.configure(plain, s, null, 0)
        }
        c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        c.start()
        codec = c
        currentSps = sps
        Log.i(TAG, "decoder started: ${c.name}")
    }

    private fun releaseCodec() {
        codec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
        currentSps = null
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private val START = byteArrayOf(0, 0, 0, 1)

        /** Split an Annex-B buffer into NAL units (without start codes). */
        fun splitNals(d: ByteArray): List<ByteArray> {
            val starts = ArrayList<Int>()   // index of first byte after a start code
            var i = 0
            while (i + 3 <= d.size) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                    if (d[i + 2].toInt() == 1) {
                        starts.add(i + 3); i += 3; continue
                    }
                    if (i + 4 <= d.size && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                        starts.add(i + 4); i += 4; continue
                    }
                }
                i++
            }
            val out = ArrayList<ByteArray>(starts.size)
            for (k in starts.indices) {
                var end = if (k + 1 < starts.size) starts[k + 1] - 3 else d.size
                // trim the extra zero of a 4-byte start code
                if (k + 1 < starts.size && end > starts[k] && d[end - 1].toInt() == 0) end--
                if (end > starts[k]) out.add(d.copyOfRange(starts[k], end))
            }
            return out
        }
    }
}
